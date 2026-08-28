package pn.torn.goldeneye.torn.service.activity.archive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.activity.TornActivityArchiveDayDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityFactionDailyDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityUserDailyDAO;
import pn.torn.goldeneye.repository.model.activity.TornActivityFactionDailyDO;
import pn.torn.goldeneye.repository.model.activity.TornActivityUserDailyDO;
import pn.torn.goldeneye.torn.service.activity.ActivityRedisKeys;
import pn.torn.goldeneye.torn.service.activity.ActivitySnapshotValidator;
import pn.torn.goldeneye.torn.service.activity.TornActivityCollectService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 活跃度V3日终压缩归档服务
 * <p>
 * 每天 00:10 Asia/Shanghai 调度归档最近未归档自然日；{@code ApplicationReadyEvent} 后异步补偿
 * 由日期 ZSET 索引发现最近 29 个已过去的自然日。两个入口通过 JVM {@code AtomicBoolean} 共享防重入
 * （当前单实例部署）。
 * <p>
 * 归档读取与 Redis 批处理不在数据库事务中执行；只有短暂的 DAO 批量 UPSERT 与最终 marker 写入
 * 采用事务。每个非空索引侧的日包都成功批量 UPSERT 后才写 marker；任一写入异常不写 marker，
 * 下次执行重试整天，日包 UPSERT 幂等。只处理 V3；只有 V2 数据的日期直接跳过。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Slf4j
@Service
public class ActivityDailyArchiveService {

    /**
     * 单批归档对象上限，控制单次 Redis 请求、内存与 JDBC batch 大小
     */
    static final int ARCHIVE_BATCH_SIZE = 500;

    /**
     * 启动补偿覆盖的最近已过去自然日数量
     */
    static final int COMPENSATION_DAYS = 29;

    private static final String DATA_VERSION_V3 = "V3";

    private final StringRedisTemplate redisTemplate;
    private final TornActivityUserDailyDAO userDailyDao;
    private final TornActivityFactionDailyDAO factionDailyDao;
    private final TornActivityArchiveDayDAO archiveDayDao;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;

    private final AtomicBoolean archiving = new AtomicBoolean(false);

    /**
     * 创建日终归档服务。
     *
     * @param redisTemplate Redis 模板
     * @param userDailyDao 用户日包 DAO
     * @param factionDailyDao 帮派日包 DAO
     * @param archiveDayDao 归档 marker DAO
     * @param virtualThreadExecutor 项目既有虚拟线程执行器
     */
    public ActivityDailyArchiveService(StringRedisTemplate redisTemplate,
                                       TornActivityUserDailyDAO userDailyDao,
                                       TornActivityFactionDailyDAO factionDailyDao,
                                       TornActivityArchiveDayDAO archiveDayDao,
                                       @Qualifier("virtualThreadExecutor") ThreadPoolTaskExecutor virtualThreadExecutor) {
        this.redisTemplate = redisTemplate;
        this.userDailyDao = userDailyDao;
        this.factionDailyDao = factionDailyDao;
        this.archiveDayDao = archiveDayDao;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * 每天 00:10 Asia/Shanghai 归档未归档自然日
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Shanghai")
    public void scheduledArchive() {
        archiveRecentUnarchivedDays();
    }

    /**
     * 启动后通过既有虚拟线程执行器异步补偿候选自然日，与定时入口共享同一防重入
     */
    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        try {
            virtualThreadExecutor.execute(this::archiveRecentUnarchivedDays);
        } catch (RejectedExecutionException e) {
            log.warn("活跃度启动归档补偿任务提交被拒绝，不影响服务启动", e);
        }
    }

    /**
     * 归档入口：从最近{@value #COMPENSATION_DAYS}个已过去自然日的日期 ZSET 中发现候选，
     * 过滤数据库 marker 后归档缺失日期；JVM 内防重入，异常在 finally 中释放。
     */
    public void archiveRecentUnarchivedDays() {
        if (!archiving.compareAndSet(false, true)) {
            log.warn("上一轮活跃度日终归档尚未完成，跳过本次调度");
            return;
        }
        try {
            LocalDate today = LocalDate.now(TornActivityCollectService.HEATMAP_ZONE);
            LocalDate minDate = today.minusDays(COMPENSATION_DAYS);
            LocalDate maxDate = today.minusDays(1);
            Set<LocalDate> candidates;
            try {
                candidates = readArchiveDateIndex(minDate, maxDate);
            } catch (Exception e) {
                log.error("活跃度归档候选日期读取失败", e);
                return;
            }
            if (candidates.isEmpty()) {
                return;
            }
            Set<LocalDate> archivedDates = archiveDayDao.selectArchivedDates(minDate, maxDate);
            for (LocalDate date : candidates) {
                if (!archivedDates.contains(date)) {
                    archiveDaySafely(date);
                }
            }
        } finally {
            archiving.set(false);
        }
    }

    /**
     * 单日归档的安全包装：失败仅记录错误，不阻断后续日期
     *
     * @param date 目标归档日期
     */
    private void archiveDaySafely(LocalDate date) {
        try {
            archiveDay(date);
        } catch (Exception e) {
            log.error("活跃度日终归档失败, date={}", date, e);
        }
    }

    /**
     * 归档单个 V3 自然日：读取归档索引、分批校验并 UPSERT 日包，每个非空索引侧全部成功后写 marker
     *
     * @param date 目标归档日期
     */
    void archiveDay(LocalDate date) {
        long startMs = System.currentTimeMillis();
        List<Long> userIds = readArchiveIndex(ActivityRedisKeys.v3ArchiveUsers(date));
        List<Long> factionIds = readArchiveIndex(ActivityRedisKeys.v3ArchiveFactions(date));
        if (userIds.isEmpty() && factionIds.isEmpty()) {
            return;
        }
        if (archiveDayDao.selectArchivedDates(date, date).contains(date)) {
            log.debug("活跃度归档 marker 已存在, date={}", date);
            return;
        }

        int userArchived = archiveUserPacks(date, userIds);
        int factionArchived = archiveFactionPacks(date, factionIds);
        int skippedIncomplete = (userIds.size() - userArchived) + (factionIds.size() - factionArchived);
        if (!isArchiveSideComplete(userIds.size(), userArchived)
                || !isArchiveSideComplete(factionIds.size(), factionArchived)) {
            log.warn("活跃度归档存在不完整日包不写 marker, date={}, userIndexed={}, userArchived={}, "
                            + "factionIndexed={}, factionArchived={}",
                    date, userIds.size(), userArchived, factionIds.size(), factionArchived);
            return;
        }

        archiveDayDao.insertMarker(date);
        log.info("活跃度日终归档完成, date={}, userIndexed={}, userArchived={}, factionIndexed={}, "
                        + "factionArchived={}, skippedIncomplete={}, elapsedMs={}",
                date, userIds.size(), userArchived, factionIds.size(), factionArchived,
                skippedIncomplete, System.currentTimeMillis() - startMs);
    }

    /**
     * 分批读取并 UPSERT 用户日包
     *
     * @param date    目标归档日期
     * @param userIds 归档索引中的用户 ID（有序）
     * @return 成功归档的用户日包数量
     */
    private int archiveUserPacks(LocalDate date, List<Long> userIds) {
        int archived = 0;
        for (int i = 0; i < userIds.size(); i += ARCHIVE_BATCH_SIZE) {
            List<Long> batch = userIds.subList(i, Math.min(i + ARCHIVE_BATCH_SIZE, userIds.size()));
            List<Object> results = pipelineGet(buildUserPackKeys(batch, date));
            List<TornActivityUserDailyDO> packs = new ArrayList<>(batch.size());
            for (int j = 0; j < batch.size(); j++) {
                byte[] observed = asBytes(results, j * 3);
                byte[] active = asBytes(results, j * 3 + 1);
                byte[] idle = asBytes(results, j * 3 + 2);
                if (!ActivitySnapshotValidator.isCompleteUserDay(observed, active, idle)) {
                    log.warn("活跃度用户日包不完整跳过, date={}", date);
                    continue;
                }
                packs.add(buildUserPack(batch.get(j), date, observed, active, idle));
            }
            if (!packs.isEmpty()) {
                userDailyDao.upsertBatch(packs);
            }
            archived += packs.size();
        }
        return archived;
    }

    /**
     * 分批读取并 UPSERT 帮派日包
     *
     * @param date       目标归档日期
     * @param factionIds 归档索引中的帮派 ID（有序）
     * @return 成功归档的帮派日包数量
     */
    private int archiveFactionPacks(LocalDate date, List<Long> factionIds) {
        int archived = 0;
        for (int i = 0; i < factionIds.size(); i += ARCHIVE_BATCH_SIZE) {
            List<Long> batch = factionIds.subList(i, Math.min(i + ARCHIVE_BATCH_SIZE, factionIds.size()));
            List<Object> results = pipelineGet(buildFactionPackKeys(batch, date));
            List<TornActivityFactionDailyDO> packs = new ArrayList<>(batch.size());
            for (int j = 0; j < batch.size(); j++) {
                byte[] observed = asBytes(results, j * 4);
                byte[] activeCounts = asBytes(results, j * 4 + 1);
                byte[] idleCounts = asBytes(results, j * 4 + 2);
                byte[] memberCounts = asBytes(results, j * 4 + 3);
                if (!ActivitySnapshotValidator.isCompleteFactionDay(
                        observed, activeCounts, idleCounts, memberCounts)) {
                    log.warn("活跃度帮派日包不完整跳过, date={}", date);
                    continue;
                }
                packs.add(buildFactionPack(batch.get(j), date, observed, activeCounts, idleCounts, memberCounts));
            }
            if (!packs.isEmpty()) {
                factionDailyDao.upsertBatch(packs);
            }
            archived += packs.size();
        }
        return archived;
    }

    /**
     * 读取单个归档索引 Set，按数值升序返回，缺失或为空时返回空列表
     *
     * @param indexKey 归档索引 key
     * @return 有序 ID 列表
     */
    private List<Long> readArchiveIndex(String indexKey) {
        Set<String> members = redisTemplate.opsForSet().members(indexKey);
        if (CollectionUtils.isEmpty(members)) {
            return List.of();
        }
        TreeSet<Long> ids = new TreeSet<>();
        for (String member : members) {
            ids.add(Long.parseLong(member));
        }
        return List.copyOf(ids);
    }

    private Set<LocalDate> readArchiveDateIndex(LocalDate minDate, LocalDate maxDate) {
        Set<String> members = redisTemplate.opsForZSet().rangeByScore(
                ActivityRedisKeys.v3ArchiveDates(), minDate.toEpochDay(), maxDate.toEpochDay());
        if (CollectionUtils.isEmpty(members)) {
            return Set.of();
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (String member : members) {
            dates.add(LocalDate.parse(member));
        }
        return dates;
    }

    private static boolean isArchiveSideComplete(int indexedCount, int archivedCount) {
        return indexedCount == 0 || indexedCount == archivedCount;
    }

    /**
     * 构建一批用户的日包 Redis key：每用户 observed/active/idle 三个
     *
     * @param batch 用户 ID 批次
     * @param date  目标归档日期
     * @return 与 Pipeline 结果顺序一致的 key 列表
     */
    private List<String> buildUserPackKeys(List<Long> batch, LocalDate date) {
        List<String> keys = new ArrayList<>(batch.size() * 3);
        for (Long userId : batch) {
            keys.add(ActivityRedisKeys.v3UserObserved(userId, date));
            keys.add(ActivityRedisKeys.v3UserActive(userId, date));
            keys.add(ActivityRedisKeys.v3UserIdle(userId, date));
        }
        return keys;
    }

    /**
     * 构建一批帮派的日包 Redis key：每帮派 observed/active/idle/member 四个
     *
     * @param batch 帮派 ID 批次
     * @param date  目标归档日期
     * @return 与 Pipeline 结果顺序一致的 key 列表
     */
    private List<String> buildFactionPackKeys(List<Long> batch, LocalDate date) {
        List<String> keys = new ArrayList<>(batch.size() * 4);
        for (Long factionId : batch) {
            keys.add(ActivityRedisKeys.v3FactionObserved(factionId, date));
            keys.add(ActivityRedisKeys.v3FactionActiveCount(factionId, date));
            keys.add(ActivityRedisKeys.v3FactionIdleCount(factionId, date));
            keys.add(ActivityRedisKeys.v3FactionMemberCount(factionId, date));
        }
        return keys;
    }

    /**
     * 单次 Pipeline 批量 GET
     *
     * @param keys key 列表
     * @return 与 key 顺序一致的 Pipeline 结果列表
     */
    private List<Object> pipelineGet(List<String> keys) {
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            for (String key : keys) {
                conn.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }, RedisSerializer.byteArray());
        if (results.size() != keys.size()) {
            throw new IllegalStateException("活跃度归档 Pipeline 结果数量不一致，期望 "
                    + keys.size() + "，实际 " + results.size());
        }
        return results;
    }

    /**
     * 按下标取 Pipeline 结果并转为 byte[]，缺失保留 null
     *
     * @param results Pipeline 结果列表
     * @param index   结果下标
     * @return 字节值或 null
     */
    private static byte[] asBytes(List<Object> results, int index) {
        Object result = results.get(index);
        return result instanceof byte[] bytes ? bytes : null;
    }

    /**
     * 构建用户日包 DO
     *
     * @param userId   用户 ID
     * @param date     归档日期
     * @param observed observed Bitmap
     * @param active   有效活跃 Bitmap
     * @param idle     idle-only Bitmap
     * @return 用户日包 DO
     */
    private static TornActivityUserDailyDO buildUserPack(long userId, LocalDate date,
                                                         byte[] observed, byte[] active, byte[] idle) {
        TornActivityUserDailyDO pack = new TornActivityUserDailyDO();
        pack.setUserId(userId);
        pack.setActivityDate(date);
        pack.setObservedBitmap(observed);
        pack.setActiveBitmap(active);
        pack.setIdleBitmap(idle);
        pack.setDataVersion(DATA_VERSION_V3);
        return pack;
    }

    /**
     * 构建帮派日包 DO
     *
     * @param factionId    帮派 ID
     * @param date         归档日期
     * @param observed     observed Bitmap
     * @param activeCounts 有效活跃人数槽值
     * @param idleCounts   idle-only 人数槽值
     * @param memberCounts 有效成员数槽值
     * @return 帮派日包 DO
     */
    private static TornActivityFactionDailyDO buildFactionPack(long factionId, LocalDate date,
                                                               byte[] observed, byte[] activeCounts,
                                                               byte[] idleCounts, byte[] memberCounts) {
        TornActivityFactionDailyDO pack = new TornActivityFactionDailyDO();
        pack.setFactionId(factionId);
        pack.setActivityDate(date);
        pack.setObservedBitmap(observed);
        pack.setActiveCounts(activeCounts);
        pack.setIdleCounts(idleCounts);
        pack.setMemberCounts(memberCounts);
        pack.setDataVersion(DATA_VERSION_V3);
        return pack;
    }
}
