package pn.torn.goldeneye.torn.service.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofDTO;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 活跃度数据采集服务
 * <p>
 * 每日 6:00 通过动态定时任务刷新黄金+帮派列表并存储成员到 Redis，
 * 每 15 分钟轮询帮派成员 last_action 时间戳写入 Redis Bitmap。
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.07
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_USER_DATA)
public class TornActivityCollectService {
    private final TornApi tornApi;
    private final StringRedisTemplate redisTemplate;
    private final DynamicTaskService taskService;
    private final SysSettingManager settingManager;
    private final ProjectProperty projectProperty;
    @Qualifier("activityCollectExecutor")
    private final ThreadPoolTaskExecutor executor;

    private static final String REDIS_KEY_PREFIX = "activity:";
    private static final String REDIS_MEMBERS_PREFIX = "faction:members:";
    private static final int POLL_INTERVAL_MINUTES = 15;
    private static final int BATCH_SIZE = 200;
    private static final int BITMAP_TTL_DAYS = 30;
    private static final int MEMBERS_TTL_DAYS = 7;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AtomicReference<List<Long>> trackedFactionIds = new AtomicReference<>(new ArrayList<>());

    /**
     * 应用启动后初始化：检查上次刷新日期，决定是否立即补刷 + 注册次日定时任务
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String lastRefreshStr = settingManager.getSettingValue(SettingConstants.KEY_ACTIVITY_FACTION_LOAD);
        LocalDate lastRefresh = DateTimeUtils.convertToDate(lastRefreshStr);
        if (lastRefresh.isBefore(LocalDate.now())) {
            refreshFactionList();
        } else {
            log.info("帮派列表今日已刷新, 跳过");
        }

        scheduleNextRefresh();
    }

    /**
     * 刷新黄金+帮派列表并存储成员到 Redis
     */
    public void refreshFactionList() {
        log.info("开始刷新帮派列表...");

        List<Long> factions = fetchGoldPlusFactions();
        if (factions.isEmpty()) {
            log.warn("帮派列表刷新失败，保持现有列表");
            scheduleNextRefresh();
            return;
        }

        trackedFactionIds.set(new ArrayList<>(factions));
        refreshFactionMembers(factions);

        settingManager.updateSetting(SettingConstants.KEY_ACTIVITY_FACTION_LOAD,
                DateTimeUtils.convertToString(LocalDate.now()));
        log.info("帮派列表刷新完成, 帮派数={}", trackedFactionIds.get().size());
        scheduleNextRefresh();
    }

    /**
     * 每 15 分钟执行一次活跃度采集
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void collectActivity() {
        List<Long> factions = new ArrayList<>(trackedFactionIds.get());
        if (factions.isEmpty()) {
            log.warn("帮派列表为空，跳过本次采集");
            return;
        }

        log.info("开始活跃度采集, 帮派数={}", factions.size());
        for (int i = 0; i < factions.size(); i += BATCH_SIZE) {
            List<Long> batch = factions.subList(i, Math.min(i + BATCH_SIZE, factions.size()));
            processBatch(batch);
        }
        log.info("活跃度采集完成");
    }

    /**
     * 注册次日 6:00 的刷新任务
     */
    private void scheduleNextRefresh() {
        LocalDateTime nextTime = LocalDate.now().plusDays(1).atTime(6, 0, 0);
        taskService.updateTask("activity-faction-refresh", this::refreshFactionList, nextTime);
    }

    /**
     * 从 Torn API 分页获取所有黄金及以上等级的帮派 ID
     *
     * @return 黄金+帮派 ID 列表
     */
    private List<Long> fetchGoldPlusFactions() {
        List<Long> result = new ArrayList<>();
        int offset = 0;
        boolean hasMore = true;

        while (hasMore) {
            TornFactionHofDTO dto = new TornFactionHofDTO("rank", 100, offset);
            TornFactionHofVO resp = tornApi.sendRequest(dto, TornFactionHofVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getFactionHof())) {
                break;
            }
            hasMore = collectGoldPlusEntries(resp, result);
            offset += 100;
        }
        return result;
    }

    /**
     * 从 factionhof 响应中收集黄金+帮派条目，遇到非黄金帮派时返回 false 停止翻页
     *
     * @param resp   factionhof API 响应
     * @param result 收集结果的目标列表
     * @return true 表示可以继续翻页，false 表示已到达黄金等级边界
     */
    private boolean collectGoldPlusEntries(TornFactionHofVO resp, List<Long> result) {
        for (TornFactionHofVO.FactionHofEntry e : resp.getFactionHof()) {
            if (!isGoldPlusRank(e.getRank())) {
                return false;
            }
            result.add(e.getId());
        }
        return true;
    }

    /**
     * 判断帮派等级是否为黄金及以上
     *
     * @param rank 帮派等级字符串
     * @return true 表示黄金/白金/钻石等级
     */
    private static boolean isGoldPlusRank(String rank) {
        return rank != null && (rank.startsWith("Diamond")
                || rank.startsWith("Platinum")
                || rank.startsWith("Gold"));
    }

    /**
     * 批量刷新帮派成员列表，通过线程池并行调用 API 存储到 Redis
     *
     * @param factions 帮派 ID 列表
     */
    private void refreshFactionMembers(List<Long> factions) {
        for (int i = 0; i < factions.size(); i += BATCH_SIZE) {
            List<Long> batch = factions.subList(i, Math.min(i + BATCH_SIZE, factions.size()));
            List<CompletableFuture<Void>> futures = batch.stream()
                    .map(fid -> CompletableFuture.runAsync(() -> storeFactionMembers(fid), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    /**
     * 存储单个帮派的成员列表到 Redis Set
     *
     * @param factionId 帮派 ID
     */
    private void storeFactionMembers(long factionId) {
        try {
            TornFactionMemberListVO resp = tornApi.sendRequest(
                    new TornFactionMemberDTO(factionId), TornFactionMemberListVO.class);
            if (resp == null || resp.getMembers() == null) return;

            String key = REDIS_MEMBERS_PREFIX + factionId;
            String[] ids = resp.getMembers().stream()
                    .map(TornFactionMemberVO::getId)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toArray(String[]::new);
            if (ids.length > 0) {
                redisTemplate.opsForSet().add(key, ids);
                redisTemplate.expire(key, Duration.ofDays(MEMBERS_TTL_DAYS));
            }
        } catch (Exception e) {
            log.warn("存储帮派 {} 成员失败: {}", factionId, e.getMessage());
        }
    }

    /**
     * 通过线程池并行处理一批帮派的活跃度采集
     *
     * @param batch 帮派 ID 批次
     */
    private void processBatch(List<Long> batch) {
        List<CompletableFuture<Void>> futures = batch.stream()
                .map(fid -> CompletableFuture.runAsync(() -> collectFaction(fid), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 采集单个帮派成员的活跃度，将在线状态写入 Redis Bitmap
     *
     * @param factionId 帮派 ID
     */
    private void collectFaction(long factionId) {
        try {
            TornFactionMemberListVO resp = tornApi.sendRequest(
                    new TornFactionMemberDTO(factionId), TornFactionMemberListVO.class);
            if (resp == null || resp.getMembers() == null) return;

            LocalDate today = LocalDate.now();
            long now = System.currentTimeMillis() / 1000;
            int slot = calculateSlotIndex();

            for (TornFactionMemberVO m : resp.getMembers()) {
                if (m.getLastAction() != null
                        && (now - m.getLastAction().getTimestamp()) < POLL_INTERVAL_MINUTES * 60L) {
                    String key = buildRedisKey(m.getId(), today);
                    redisTemplate.opsForValue().setBit(key, slot, true);
                    redisTemplate.expire(key, Duration.ofDays(BITMAP_TTL_DAYS));
                }
            }
        } catch (Exception e) {
            log.warn("采集帮派 {} 失败: {}", factionId, e.getMessage());
        }
    }

    /**
     * 计算当前时间对应的 Bitmap 槽位索引（每天 96 个槽，每 15 分钟一个）
     *
     * @return 槽位索引 (0-95)
     */
    private int calculateSlotIndex() {
        LocalTime now = LocalTime.now();
        return now.getHour() * 4 + now.getMinute() / 15;
    }

    /**
     * 构建 Redis Bitmap Key：activity:{userId}:{yyyy-MM-dd}
     *
     * @param userId 用户 ID
     * @param date   日期
     * @return Redis Key
     */
    static String buildRedisKey(long userId, LocalDate date) {
        return REDIS_KEY_PREFIX + userId + ":" + date.format(DATE_FMT);
    }
}
