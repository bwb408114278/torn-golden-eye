package pn.torn.goldeneye.torn.service.activity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.activity.ActivityEvidence;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofDTO;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 活跃度数据采集服务
 * <p>
 * 每日 6:00 通过动态定时任务刷新黄金+帮派列表，与{@code torn_setting_faction}有效配置帮派
 * 按 ID 并集后发布采集名单并存储成员到 Redis；
 * 每 15 分钟轮询帮派成员 last_action 时间戳写入 Redis V3 Bitmap（active/idle互斥三态），
 * 并在同一 Pipeline 写入日终归档索引 Set。不再写 V2 新数据，V2 Key 仅保留查询端兼容读取。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.07
 */
@Slf4j
@Service
@Order(InitOrderConstants.TORN_USER_DATA)
public class TornActivityCollectService {
    private final TornApi tornApi;
    private final StringRedisTemplate redisTemplate;
    private final DynamicTaskService taskService;
    private final SysSettingManager settingManager;
    private final ProjectProperty projectProperty;
    private final TornSettingFactionManager settingFactionManager;
    @Qualifier("activityCollectExecutor")
    private final SimpleAsyncTaskExecutor executor;

    /**
     * 热力图产品时区（采集、归档与查询范围解析共用）
     */
    public static final ZoneId HEATMAP_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String REDIS_MEMBERS_PREFIX = "faction:members:";
    private static final int COLLECTION_BATCH_SIZE = 50;
    private static final int BITMAP_TTL_DAYS = 30;
    private static final int MEMBERS_TTL_DAYS = 7;
    private static final String REDIS_TRACKED_FACTIONS_KEY = "faction:tracked";
    private static final int TRACKED_FACTIONS_TTL_DAYS = 7;

    /**
     * 最近一次成功 HoF 刷新的 Gold+ 帮派 ID 来源；刷新失败时保留
     */
    private final AtomicReference<List<Long>> goldPlusFactionIds = new AtomicReference<>(List.of());
    /**
     * 发布的采集帮派并集名单（Gold+ ∪ 有效配置帮派），不可变
     */
    private final AtomicReference<List<Long>> trackedFactionIds = new AtomicReference<>(List.of());
    private final AtomicBoolean collecting = new AtomicBoolean(false);

    /**
     * 创建活跃度采集服务。
     *
     * @param tornApi              Torn API 客户端
     * @param redisTemplate        Redis 操作模板
     * @param taskService          动态任务服务
     * @param settingManager       系统设置管理器
     * @param projectProperty      项目配置
     * @param settingFactionManager 帮派配置管理器（提供有效配置帮派来源）
     * @param executor             活跃度采集专用执行器
     */
    public TornActivityCollectService(TornApi tornApi,
                                      StringRedisTemplate redisTemplate,
                                      DynamicTaskService taskService,
                                      SysSettingManager settingManager,
                                      ProjectProperty projectProperty,
                                      TornSettingFactionManager settingFactionManager,
                                      @Qualifier("activityCollectExecutor") SimpleAsyncTaskExecutor executor) {
        this.tornApi = tornApi;
        this.redisTemplate = redisTemplate;
        this.taskService = taskService;
        this.settingManager = settingManager;
        this.projectProperty = projectProperty;
        this.settingFactionManager = settingFactionManager;
        this.executor = executor;
    }

    /**
     * 应用启动后初始化：检查上次刷新日期，决定是否立即补刷 + 注册次日定时任务
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        // 先从 Redis 恢复上次刷新的帮派列表，避免重启后数据丢失
        loadFactionListFromRedis();

        String lastRefreshStr = settingManager.getSettingValue(SettingConstants.KEY_ACTIVITY_FACTION_LOAD);
        LocalDate lastRefresh = DateTimeUtils.convertToDate(lastRefreshStr);
        boolean needRefresh = trackedFactionIds.get().isEmpty()
                || lastRefresh.isBefore(LocalDate.now());
        if (needRefresh) {
            refreshFactionList();
        } else {
            log.info("帮派列表已从 Redis 恢复, 帮派数={}, 今日已刷新跳过", trackedFactionIds.get().size());
        }

        scheduleNextRefresh();
    }

    /**
     * 每 15 分钟执行一次活跃度采集
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void collectActivity() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        if (!collecting.compareAndSet(false, true)) {
            log.warn("上一轮活跃度采集尚未完成，跳过本次调度");
            return;
        }

        long startTime = System.currentTimeMillis();
        List<Long> factions = new ArrayList<>(trackedFactionIds.get());
        int successCount = 0;
        int failureCount = 0;
        try {
            if (factions.isEmpty()) {
                log.warn("帮派列表为空，跳过本次采集");
                return;
            }

            log.info("开始活跃度采集, 帮派数={}", factions.size());
            for (int i = 0; i < factions.size(); i += COLLECTION_BATCH_SIZE) {
                List<Long> batch = factions.subList(i, Math.min(i + COLLECTION_BATCH_SIZE, factions.size()));
                BatchResult result = processBatch(batch);
                successCount += result.successCount();
                failureCount += result.failureCount();
            }
        } finally {
            collecting.set(false);
            log.info("活跃度采集完成, 帮派数={}, 成功={}, 失败={}, 耗时={}ms",
                    factions.size(), successCount, failureCount, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 刷新帮派采集名单：更新 Gold+ 来源、合并有效配置帮派并集、刷新名称缓存
     * <p>
     * HoF 刷新失败或空响应时保留上次成功 Gold+ 来源，仍实时读取配置来源合并，
     * 已配置低段位帮派不因 HoF 失败停止采集。
     */
    public void refreshFactionList() {
        log.info("开始刷新帮派列表...");

        List<TornFactionHofVO.FactionHofEntry> entries = fetchGoldPlusEntries();
        List<TornFactionHofVO.FactionHofEntry> hofNameEntries;
        if (entries.isEmpty()) {
            log.warn("帮派列表刷新失败，保持现有 Gold+ 来源");
            hofNameEntries = List.of();
        } else {
            goldPlusFactionIds.set(entries.stream().map(TornFactionHofVO.FactionHofEntry::getId).toList());
            hofNameEntries = entries;
        }

        List<TornSettingFactionDO> settingFactions = settingFactionManager.getList();
        List<Long> merged = mergeTrackedFactionIds(goldPlusFactionIds.get(), settingFactions);
        trackedFactionIds.set(merged);

        // 持久化帮派列表到 Redis，重启后可恢复（Redis 异常不影响核心流程）
        try {
            persistFactionListToRedis(merged);
            persistFactionNamesToRedis(hofNameEntries, settingFactions);
        } catch (Exception e) {
            log.warn("帮派列表持久化到 Redis 失败: {}", e.getMessage());
        }

        settingManager.updateSetting(SettingConstants.KEY_ACTIVITY_FACTION_LOAD,
                DateTimeUtils.convertToString(LocalDate.now()));
        log.info("帮派列表刷新完成, 帮派数={}", trackedFactionIds.get().size());
        scheduleNextRefresh();
    }

    /**
     * 通过线程池并行处理一批帮派的活跃度采集
     *
     * @param batch 帮派 ID 批次
     */
    BatchResult processBatch(List<Long> batch) {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(batch.size());
        for (Long factionId : batch) {
            try {
                futures.add(CompletableFuture.supplyAsync(() -> collectFaction(factionId), executor));
            } catch (RejectedExecutionException e) {
                log.warn("活跃度采集任务提交被拒绝, 已提交={}, 未提交={}",
                        futures.size(), batch.size() - futures.size(), e);
                break;
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        int successCount = (int) futures.stream().filter(CompletableFuture::join).count();
        return new BatchResult(successCount, batch.size() - successCount);
    }

    /**
     * 从 Redis 加载已持久化的帮派列表到内存
     */
    private void loadFactionListFromRedis() {
        Set<String> members = redisTemplate.opsForSet().members(REDIS_TRACKED_FACTIONS_KEY);
        if (members != null && !members.isEmpty()) {
            List<Long> ids = members.stream().map(Long::parseLong).toList();
            trackedFactionIds.set(new ArrayList<>(ids));
            log.info("从 Redis 加载帮派列表, 帮派数={}", ids.size());
        }
    }

    /**
     * 将帮派ID列表持久化到 Redis Set，供重启恢复
     *
     * @param factions 帮派ID列表
     */
    private void persistFactionListToRedis(List<Long> factions) {
        String[] ids = factions.stream().map(String::valueOf).toArray(String[]::new);
        redisTemplate.delete(REDIS_TRACKED_FACTIONS_KEY);
        redisTemplate.opsForSet().add(REDIS_TRACKED_FACTIONS_KEY, ids);
        redisTemplate.expire(REDIS_TRACKED_FACTIONS_KEY, Duration.ofDays(TRACKED_FACTIONS_TTL_DAYS));
        log.debug("帮派列表已持久化到 Redis, 帮派数={}", factions.size());
    }

    /**
     * 将帮派名称批量写入名称缓存 Hash：先写 HoF 名称，再以配置表名称补充；
     * 配置表由运维维护视为更可靠来源，与 HoF 重叠时覆盖
     *
     * @param hofEntries      本轮 HoF 黄金+帮派条目列表（失败时为空列表）
     * @param settingFactions 当前有效配置帮派列表
     */
    private void persistFactionNamesToRedis(List<TornFactionHofVO.FactionHofEntry> hofEntries,
                                            List<TornSettingFactionDO> settingFactions) {
        Map<String, String> nameMap = HashMap.newHashMap(hofEntries.size() + settingFactions.size());
        for (TornFactionHofVO.FactionHofEntry e : hofEntries) {
            if (e.getName() != null && !e.getName().isBlank()) {
                nameMap.put(ActivityRedisKeys.factionNameField(e.getId()), e.getName());
            }
        }
        for (TornSettingFactionDO setting : settingFactions) {
            if (setting.getFactionName() != null && !setting.getFactionName().isBlank()) {
                nameMap.put(ActivityRedisKeys.factionNameField(setting.getId()), setting.getFactionName());
            }
        }
        if (!nameMap.isEmpty()) {
            redisTemplate.opsForHash().putAll(ActivityRedisKeys.FACTION_NAME_CACHE_KEY, nameMap);
        }
    }

    /**
     * 按帮派 ID 去重并排序，合并 Gold+ 来源与有效配置帮派来源
     *
     * @param goldPlusIds     最近一次成功 HoF 刷新的 Gold+ 帮派 ID 集合
     * @param settingFactions 当前有效配置帮派列表
     * @return 去重升序的不可变采集名单
     */
    static List<Long> mergeTrackedFactionIds(List<Long> goldPlusIds, List<TornSettingFactionDO> settingFactions) {
        TreeSet<Long> mergedIds = new TreeSet<>(goldPlusIds);
        if (!CollectionUtils.isEmpty(settingFactions)) {
            settingFactions.stream()
                    .map(TornSettingFactionDO::getId)
                    .filter(Objects::nonNull)
                    .forEach(mergedIds::add);
        }
        return List.copyOf(mergedIds);
    }

    /**
     * 注册次日 6:00 的刷新任务
     */
    private void scheduleNextRefresh() {
        LocalDateTime nextTime = LocalDate.now().plusDays(1).atTime(6, 0, 0);
        taskService.updateTask("activity-faction-refresh", this::refreshFactionList, nextTime);
    }

    /**
     * 从 Torn API 分页获取所有黄金及以上等级的帮派条目
     *
     * @return 黄金+帮派条目列表（含 ID 和名称）
     */
    private List<TornFactionHofVO.FactionHofEntry> fetchGoldPlusEntries() {
        List<TornFactionHofVO.FactionHofEntry> result = new ArrayList<>();
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
    private boolean collectGoldPlusEntries(TornFactionHofVO resp, List<TornFactionHofVO.FactionHofEntry> result) {
        for (TornFactionHofVO.FactionHofEntry e : resp.getFactionHof()) {
            if (!isGoldPlusRank(e.getRank())) {
                return false;
            }
            result.add(e);
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
     * 采集单个帮派成员的活跃度，将 V3 三态 Bitmap、帮派聚合快照和日终归档索引写入 Redis，
     * 并同步更新成员列表缓存
     * <p>
     * 所有 Redis 写操作通过单个 Pipeline 批量提交，将 N 次网络往返压缩为 1 次；
     * API 失败时不写任何 observed、归档索引与成员快照。
     *
     * @param factionId 帮派 ID
     */
    private boolean collectFaction(long factionId) {
        String membersKey = REDIS_MEMBERS_PREFIX + factionId;
        String temporaryMembersKey = membersKey + ":tmp:" + UUID.randomUUID();
        try {
            TornFactionMemberListVO resp = tornApi.sendRequest(
                    new TornFactionMemberDTO(factionId), TornFactionMemberListVO.class);
            if (resp == null || resp.getMembers() == null) {
                return false;
            }

            LocalDateTime collectionTime = LocalDateTime.now(HEATMAP_ZONE);
            LocalDate today = collectionTime.toLocalDate();
            int slot = calculateSlotIndex(collectionTime);
            Duration bitmapTtl = Duration.ofDays(BITMAP_TTL_DAYS);
            Duration membersTtl = Duration.ofDays(MEMBERS_TTL_DAYS);

            long collectedAtEpochSecond = collectionTime.atZone(HEATMAP_ZONE).toEpochSecond();
            CollectionMetadata metadata = new CollectionMetadata(
                    collectedAtEpochSecond, today, slot, bitmapTtl, membersTtl,
                    temporaryMembersKey, membersKey);
            CollectionContext ctx = prepareCollectionContext(resp, metadata);
            executeRedisPipeline(factionId, ctx);
            return true;
        } catch (Exception e) {
            cleanupTemporaryMembersKey(temporaryMembersKey, factionId);
            log.warn("采集帮派 {} 失败", factionId, e);
            return false;
        }
    }

    private void cleanupTemporaryMembersKey(String temporaryMembersKey, long factionId) {
        try {
            redisTemplate.delete(temporaryMembersKey);
        } catch (Exception cleanupException) {
            log.warn("清理帮派 {} 临时成员集合失败", factionId, cleanupException);
        }
    }

    /**
     * 采集预计算上下文，避免在 Pipeline lambda 中重复流操作
     *
     * @param allMemberIds     全部有效成员 ID
     * @param activeUserIds    有效活跃（Online 或 15 分钟内动作）的成员 ID
     * @param idleOnlyUserIds  idle-only（Idle 且无近期动作）的成员 ID
     * @param userNameMap      用户名称映射
     * @param today            今天日期
     * @param slot             当前槽位
     * @param bitmapTtl        Bitmap TTL
     * @param membersTtl       成员集合 TTL
     * @param temporaryMembersKey 临时成员集合 key
     * @param membersKey       成员集合 key
     */
    private record CollectionContext(
            List<Long> allMemberIds,
            List<Long> activeUserIds,
            List<Long> idleOnlyUserIds,
            Map<String, String> userNameMap,
            LocalDate today,
            int slot,
            Duration bitmapTtl,
            Duration membersTtl,
            String temporaryMembersKey,
            String membersKey) {
    }

    /**
     * 单次帮派采集的不可变元数据，确保时间、槽位、TTL 和成员快照 key 使用同一上下文。
     *
     * @param collectedAtEpochSecond 本轮统一采集时刻的 epoch 秒
     * @param today                  当前采集日期
     * @param slot                   当前 15 分钟槽位
     * @param bitmapTtl              活跃度数据 TTL
     * @param membersTtl             成员快照 TTL
     * @param temporaryMembersKey    临时成员快照 key
     * @param membersKey             正式成员快照 key
     */
    private record CollectionMetadata(
            long collectedAtEpochSecond,
            LocalDate today,
            int slot,
            Duration bitmapTtl,
            Duration membersTtl,
            String temporaryMembersKey,
            String membersKey) {
    }

    /**
     * 预计算采集判定结果和成员 ID 列表
     *
     * @param resp     帮派成员列表响应
     * @param metadata 单次采集元数据
     * @return 采集上下文
     */
    private CollectionContext prepareCollectionContext(
            TornFactionMemberListVO resp, CollectionMetadata metadata) {
        List<Long> allMemberIds = new ArrayList<>();
        List<Long> activeUserIds = new ArrayList<>();
        List<Long> idleOnlyUserIds = new ArrayList<>();
        Map<String, String> userNameMap = HashMap.newHashMap(resp.getMembers().size());

        for (TornFactionMemberVO m : resp.getMembers()) {
            if (m.getId() == null) {
                continue;
            }
            long userId = m.getId();
            allMemberIds.add(userId);

            if (m.getName() != null && !m.getName().isBlank()) {
                userNameMap.put(ActivityRedisKeys.userNameField(userId), m.getName());
            }

            ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(
                    m.getLastAction(), metadata.collectedAtEpochSecond());
            if (evidence.effectiveActive()) {
                activeUserIds.add(userId);
            }
            if (evidence.idleOnly()) {
                idleOnlyUserIds.add(userId);
            }
        }

        return new CollectionContext(allMemberIds, activeUserIds, idleOnlyUserIds,
                userNameMap, metadata.today(), metadata.slot(),
                metadata.bitmapTtl(), metadata.membersTtl(), metadata.temporaryMembersKey(),
                metadata.membersKey());
    }

    /**
     * 单个 Pipeline 批量提交所有 V3 Redis 写命令
     *
     * @param factionId 帮派 ID
     * @param ctx       采集上下文（含日期、槽位、TTL、key 等全部信息）
     */
    private void executeRedisPipeline(long factionId, CollectionContext ctx) {
        FactionSlotCounts slotCounts = new FactionSlotCounts(
                encodeSlotValue(ctx.activeUserIds().size()),
                encodeSlotValue(ctx.idleOnlyUserIds().size()),
                encodeSlotValue(ctx.allMemberIds().size()));
        String[] memberIdArray = ctx.allMemberIds().stream().map(String::valueOf).toArray(String[]::new);

        redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            // 1. V3 个人维度：每个有效成员写 observed Bitmap
            for (Long userId : ctx.allMemberIds()) {
                byte[] key = ActivityRedisKeys.v3UserObserved(userId, ctx.today())
                        .getBytes(StandardCharsets.UTF_8);
                conn.stringCommands().setBit(key, ctx.slot(), true);
                conn.keyCommands().expire(key, ctx.bitmapTtl().toSeconds());
            }

            // 2. V3 个人维度：有效活跃 Bitmap（显式布尔状态支持同槽重采清除旧 true 位）
            writeBitmapSlot(conn, ctx.allMemberIds(), ctx.activeUserIds(),
                    ctx.today(), ctx.slot(), ctx.bitmapTtl(),
                    ActivityRedisKeys::v3UserActive);

            // 3. V3 个人维度：idle-only Bitmap
            writeBitmapSlot(conn, ctx.allMemberIds(), ctx.idleOnlyUserIds(),
                    ctx.today(), ctx.slot(), ctx.bitmapTtl(),
                    ActivityRedisKeys::v3UserIdle);

            // 4. V3 帮派维度：active/idle/member 槽值与 observed Bitmap
            writeFactionSlot(conn, factionId, ctx.today(), ctx.slot(), ctx.bitmapTtl(), slotCounts);

            // 5. V3 日终归档索引：当日 observed 用户集与成功采集帮派集
            writeArchiveIndex(conn, factionId, ctx);

            // 6. 名称缓存：用户名
            writeUserNameCache(conn, ctx.userNameMap());

            // 7. 通过临时集合原子替换成员快照，避免查询端观察到空集合
            replaceMemberSnapshot(conn, memberIdArray, ctx.temporaryMembersKey(),
                    ctx.membersKey(), ctx.membersTtl());
            return null;
        });
    }

    /**
     * 批量写入用户活跃 Bitmap 单槽位
     *
     * @param conn          Redis 连接
     * @param allMemberIds  全部有效成员 ID
     * @param evidenceUserIds 当前证据成立的用户 ID
     * @param today         今天日期
     * @param slot          槽位
     * @param bitmapTtl     Bitmap TTL
     * @param keyBuilder    key 构造函数
     */
    private void writeBitmapSlot(org.springframework.data.redis.connection.RedisConnection conn,
                                 List<Long> allMemberIds, List<Long> evidenceUserIds,
                                 LocalDate today, int slot, Duration bitmapTtl,
                                 java.util.function.BiFunction<Long, LocalDate, String> keyBuilder) {
        for (Map.Entry<Long, Boolean> state : buildEvidenceStates(allMemberIds, evidenceUserIds).entrySet()) {
            Long userId = state.getKey();
            byte[] key = keyBuilder.apply(userId, today).getBytes(StandardCharsets.UTF_8);
            conn.stringCommands().setBit(key, slot, state.getValue());
            conn.keyCommands().expire(key, bitmapTtl.toSeconds());
        }
    }

    /**
     * 帮派维度单槽三类计数值（各为长度 1 的无符号字节数组）。
     *
     * @param activeCount 有效活跃人数槽值
     * @param idleCount   idle-only 人数槽值
     * @param memberCount 有效成员数槽值
     */
    private record FactionSlotCounts(
            byte[] activeCount,
            byte[] idleCount,
            byte[] memberCount) {
    }

    /**
     * 写入 V3 帮派维度槽数据
     *
     * @param conn       Redis 连接
     * @param factionId  帮派 ID
     * @param today      今天日期
     * @param slot       槽位
     * @param bitmapTtl  Bitmap TTL
     * @param slotCounts 帮派单槽三类计数值
     */
    private void writeFactionSlot(org.springframework.data.redis.connection.RedisConnection conn,
                                  long factionId, LocalDate today, int slot, Duration bitmapTtl,
                                  FactionSlotCounts slotCounts) {
        byte[] activeCountKey = ActivityRedisKeys.v3FactionActiveCount(factionId, today)
                .getBytes(StandardCharsets.UTF_8);
        conn.stringCommands().setRange(activeCountKey, slotCounts.activeCount(), slot);
        conn.keyCommands().expire(activeCountKey, bitmapTtl.toSeconds());

        byte[] idleCountKey = ActivityRedisKeys.v3FactionIdleCount(factionId, today)
                .getBytes(StandardCharsets.UTF_8);
        conn.stringCommands().setRange(idleCountKey, slotCounts.idleCount(), slot);
        conn.keyCommands().expire(idleCountKey, bitmapTtl.toSeconds());

        byte[] memberCountKey = ActivityRedisKeys.v3FactionMemberCount(factionId, today)
                .getBytes(StandardCharsets.UTF_8);
        conn.stringCommands().setRange(memberCountKey, slotCounts.memberCount(), slot);
        conn.keyCommands().expire(memberCountKey, bitmapTtl.toSeconds());

        byte[] observedKey = ActivityRedisKeys.v3FactionObserved(factionId, today)
                .getBytes(StandardCharsets.UTF_8);
        conn.stringCommands().setBit(observedKey, slot, true);
        conn.keyCommands().expire(observedKey, bitmapTtl.toSeconds());
    }

    /**
     * 写入 V3 日终归档索引：当日 observed 用户 Set 与成功采集帮派 Set
     *
     * @param conn      Redis 连接
     * @param factionId 本轮成功采集的帮派 ID
     * @param ctx       采集上下文
     */
    private void writeArchiveIndex(org.springframework.data.redis.connection.RedisConnection conn,
                                   long factionId, CollectionContext ctx) {
        byte[] archiveUsersKey = ActivityRedisKeys.v3ArchiveUsers(ctx.today())
                .getBytes(StandardCharsets.UTF_8);
        byte[][] userIdMembers = ctx.allMemberIds().stream()
                .map(id -> String.valueOf(id).getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
        conn.setCommands().sAdd(archiveUsersKey, userIdMembers);
        conn.keyCommands().expire(archiveUsersKey, ctx.bitmapTtl().toSeconds());

        byte[] archiveFactionsKey = ActivityRedisKeys.v3ArchiveFactions(ctx.today())
                .getBytes(StandardCharsets.UTF_8);
        conn.setCommands().sAdd(archiveFactionsKey,
                String.valueOf(factionId).getBytes(StandardCharsets.UTF_8));
        conn.keyCommands().expire(archiveFactionsKey, ctx.bitmapTtl().toSeconds());
    }

    /**
     * 写入用户名称缓存 Hash
     *
     * @param conn        Redis 连接
     * @param userNameMap 用户名映射
     */
    private void writeUserNameCache(org.springframework.data.redis.connection.RedisConnection conn,
                                    Map<String, String> userNameMap) {
        if (userNameMap.isEmpty()) {
            return;
        }
        byte[] userNameCacheRedisKeyBytes = ActivityRedisKeys.USER_NAME_CACHE_KEY
                .getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : userNameMap.entrySet()) {
            conn.hashCommands().hSet(userNameCacheRedisKeyBytes,
                    entry.getKey().getBytes(StandardCharsets.UTF_8),
                    entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 通过临时集合原子替换成员快照
     *
     * @param conn                Redis 连接
     * @param memberIdArray       成员 ID 数组
     * @param temporaryMembersKey 临时成员集合 key
     * @param membersKey          成员集合 key
     * @param membersTtl          成员集合 TTL
     */
    private void replaceMemberSnapshot(org.springframework.data.redis.connection.RedisConnection conn,
                                       String[] memberIdArray, String temporaryMembersKey,
                                       String membersKey, Duration membersTtl) {
        if (memberIdArray.length == 0) {
            conn.keyCommands().del(membersKey.getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] tmpKey = temporaryMembersKey.getBytes(StandardCharsets.UTF_8);
        conn.setCommands().sAdd(tmpKey,
                Arrays.stream(memberIdArray).map(s -> s.getBytes(StandardCharsets.UTF_8))
                        .toArray(byte[][]::new));
        conn.keyCommands().expire(tmpKey, membersTtl.toSeconds());
        conn.keyCommands().rename(tmpKey, membersKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将帮派单槽计数编码为一个无符号字节。
     *
     * @param slotValue 当前槽的计数值
     * @return 长度为 1 的字节数组
     */
    static byte[] encodeSlotValue(int slotValue) {
        if (slotValue < 0 || slotValue > 255) {
            throw new IllegalArgumentException("帮派槽位值超出 1 字节范围: " + slotValue);
        }
        return new byte[]{(byte) slotValue};
    }

    /**
     * 为全部成员生成当前证据槽的显式布尔状态，支持同槽重采时清除旧的 true 位。
     *
     * @param allMemberIds  全部有效成员 ID
     * @param evidenceUserIds 当前证据成立的成员 ID
     * @return 用户 ID 到当前槽状态的映射
     */
    static Map<Long, Boolean> buildEvidenceStates(List<Long> allMemberIds, List<Long> evidenceUserIds) {
        Set<Long> evidenceUserIdSet = new HashSet<>(evidenceUserIds);
        Map<Long, Boolean> states = HashMap.newHashMap(allMemberIds.size());
        for (Long userId : allMemberIds) {
            states.put(userId, evidenceUserIdSet.contains(userId));
        }
        return states;
    }

    /**
     * 计算当前时间对应的 Bitmap 槽位索引（每天 96 个槽，每 15 分钟一个）
     *
     * @return 槽位索引 (0-95)
     */
    static int calculateSlotIndex(LocalDateTime collectionTime) {
        return collectionTime.getHour() * 4 + collectionTime.getMinute() / 15;
    }

    record BatchResult(int successCount, int failureCount) {
    }
}
