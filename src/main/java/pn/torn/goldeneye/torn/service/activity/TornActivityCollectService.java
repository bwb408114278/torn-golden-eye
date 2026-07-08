package pn.torn.goldeneye.torn.service.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofDTO;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 活跃度数据采集服务
 * <p>
 * 每 15 分钟轮询黄金+帮派的成员 last_action 时间戳，
 * 写入 Redis Bitmap。
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornActivityCollectService {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final StringRedisTemplate redisTemplate;
    @Qualifier("activityCollectExecutor")
    private final ThreadPoolTaskExecutor executor;

    private static final String REDIS_KEY_PREFIX = "activity:";
    private static final int POLL_INTERVAL_MINUTES = 15;
    private static final int BATCH_SIZE = 200;
    private static final int BITMAP_TTL_DAYS = 30;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 当前追踪的帮派ID列表，每日刷新
     */
    private volatile List<Long> trackedFactionIds = new CopyOnWriteArrayList<>();
    private volatile LocalDate factionListLastRefresh;

    // ==================== 定时任务 ====================

    /**
     * 每 15 分钟执行一次活跃度采集
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void collectActivity() {
        if (!ensureFactionList()) {
            return;
        }

        List<Long> factions = new ArrayList<>(trackedFactionIds);
        log.info("开始活跃度采集, 帮派数={}", factions.size());

        // 分组：每 BATCH_SIZE 个帮派一批
        for (int i = 0; i < factions.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, factions.size());
            List<Long> batch = factions.subList(i, end);
            processBatch(batch);
        }

        log.info("活跃度采集完成, 帮派数={}", factions.size());
    }

    // ==================== 帮派列表管理 ====================

    private boolean ensureFactionList() {
        if (trackedFactionIds.isEmpty() || needRefreshFactionList()) {
            try {
                refreshFactionList();
            } catch (Exception e) {
                log.error("刷新帮派列表失败", e);
                return !trackedFactionIds.isEmpty();
            }
        }
        return !trackedFactionIds.isEmpty();
    }

    private boolean needRefreshFactionList() {
        return factionListLastRefresh == null
                || factionListLastRefresh.plusDays(1).isBefore(LocalDate.now());
    }

    private void refreshFactionList() {
        log.info("从Torn Api拉取黄金以上帮派列表...");
        List<Long> allFactions = new ArrayList<>();
        int offset = 0;

        while (true) {
            TornFactionHofDTO dto = new TornFactionHofDTO("rank", 100, offset);
            TornFactionHofVO resp = tornApi.sendRequest(dto, TornFactionHofVO.class);

            if (resp == null || resp.getFactionHof() == null) {
                log.warn("Torn Api返回数据异常, offset={}", offset);
                break;
            }

            for (TornFactionHofVO.FactionHofEntry entry : resp.getFactionHof()) {
                String rank = entry.getRank();
                if (rank != null && (rank.startsWith("Diamond")
                        || rank.startsWith("Platinum")
                        || rank.startsWith("Gold"))) {
                    allFactions.add(entry.getId());
                } else {
                    break;
                }
            }

            offset += 100;
        }

        this.trackedFactionIds = new CopyOnWriteArrayList<>(allFactions);
        this.factionListLastRefresh = LocalDate.now();
        log.info("帮派列表刷新完成, 帮派数={}", trackedFactionIds.size());
    }

    // ==================== 分批处理 ====================

    private void processBatch(List<Long> batch) {
        List<TornApiKeyDO> keys = apiKeyConfig.getAllEnableKeys();
        if (keys.isEmpty()) {
            log.warn("无可用 API Key");
            return;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            final long factionId = batch.get(i);
            final TornApiKeyDO key = keys.get(i % keys.size());
            futures.add(CompletableFuture.runAsync(() -> collectFaction(factionId, key), executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    // ==================== 单个帮派采集 ====================

    private void collectFaction(long factionId, TornApiKeyDO key) {
        try {
            TornFactionMemberDTO param = new TornFactionMemberDTO(factionId);
            TornFactionMemberListVO resp = tornApi.sendRequest(param, key, TornFactionMemberListVO.class);

            if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
                return;
            }

            LocalDate today = LocalDate.now();
            long nowEpoch = System.currentTimeMillis() / 1000;

            for (TornFactionMemberVO member : resp.getMembers()) {
                // 判定活跃：last_action 在 15 分钟内
                boolean isActive = member.getLastAction() != null
                        && (nowEpoch - member.getLastAction().getTimestamp()) < POLL_INTERVAL_MINUTES * 60L;

                if (!isActive) continue;

                // 计算 15 分钟槽位索引: (hour * 4 + minuteSlot)
                int slotIndex = calculateSlotIndex();
                String redisKey = buildRedisKey(member.getId(), today);
                redisTemplate.opsForValue().setBit(redisKey, slotIndex, true);
                redisTemplate.expire(redisKey, Duration.ofDays(BITMAP_TTL_DAYS));
            }
        } catch (Exception e) {
            log.warn("采集帮派 {} 失败: {}", factionId, e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 计算当前时刻对应的 15 分钟槽位索引 (0-95)
     */
    private int calculateSlotIndex() {
        LocalTime now = LocalTime.now();
        int slotInHour = now.getMinute() / 15; // 0, 1, 2, 3
        return now.getHour() * 4 + slotInHour;
    }

    /**
     * 构建 Redis Key: activity:{userId}:{date}
     */
    static String buildRedisKey(long userId, LocalDate date) {
        return REDIS_KEY_PREFIX + userId + ":" + date.format(DATE_FMT);
    }

    // ==================== 查询辅助（供 ActivityHeatmapService 使用） ====================

    /**
     * 获取追踪的帮派ID列表
     */
    public List<Long> getTrackedFactionIds() {
        return new ArrayList<>(trackedFactionIds);
    }
}
