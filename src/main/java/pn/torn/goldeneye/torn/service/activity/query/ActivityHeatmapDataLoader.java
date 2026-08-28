package pn.torn.goldeneye.torn.service.activity.query;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.activity.TornActivityFactionDailyDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityUserDailyDAO;
import pn.torn.goldeneye.repository.model.activity.TornActivityFactionDailyDO;
import pn.torn.goldeneye.repository.model.activity.TornActivityUserDailyDO;
import pn.torn.goldeneye.torn.service.activity.ActivityRedisKeys;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * 活跃度热力图三版本数据源加载器
 * <p>
 * 统一封装按自然日的数据源优先级：V3 PostgreSQL 归档 → V3 Redis → V2 Redis → 无数据，
 * 同一日期只取一个版本，加载结果不重复累计；个人、帮派、对比查询共用同一加载边界。
 * V2 legacy 日快照不携带 idle 信息；个人 active 为 status-active 与 recent-action 按位 OR，
 * 语义与 V2 展示口径（Online OR Idle OR recentAction）一致。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Component
@RequiredArgsConstructor
public class ActivityHeatmapDataLoader {

    private final StringRedisTemplate redisTemplate;
    private final TornActivityUserDailyDAO userDailyDao;
    private final TornActivityFactionDailyDAO factionDailyDao;

    /**
     * 加载用户在指定日期列表内的日快照，按输入日期顺序返回存在的快照
     *
     * @param userId 用户 ID
     * @param dates  查询日期列表（升序）
     * @return 命中数据源的日快照列表，同一日期至多一个
     */
    public List<ActivityDaySnapshot.UserDay> loadUserDays(long userId, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, ActivityDaySnapshot.UserDay> byDate = HashMap.newHashMap(dates.size());
        for (TornActivityUserDailyDO row : userDailyDao.selectByUserAndDateRange(
                userId, dates.getFirst(), dates.getLast())) {
            byDate.putIfAbsent(row.getActivityDate(), new ActivityDaySnapshot.UserDay(
                    row.getActivityDate(), false,
                    row.getObservedBitmap(), row.getActiveBitmap(), row.getIdleBitmap()));
        }

        List<LocalDate> missingV3 = datesNotLoaded(dates, byDate);
        if (!missingV3.isEmpty()) {
            List<Object> results = pipelineGet(buildUserV3Keys(userId, missingV3));
            for (int i = 0; i < missingV3.size(); i++) {
                byte[] observed = asBytes(results, i * 3);
                if (observed == null) {
                    continue;
                }
                LocalDate date = missingV3.get(i);
                byDate.put(date, new ActivityDaySnapshot.UserDay(date, false,
                        observed, asBytes(results, i * 3 + 1), asBytes(results, i * 3 + 2)));
            }
        }

        List<LocalDate> missingV2 = datesNotLoaded(dates, byDate);
        if (!missingV2.isEmpty()) {
            List<Object> results = pipelineGet(buildUserV2Keys(userId, missingV2));
            for (int i = 0; i < missingV2.size(); i++) {
                byte[] observed = asBytes(results, i * 3);
                if (observed == null) {
                    continue;
                }
                LocalDate date = missingV2.get(i);
                byDate.put(date, new ActivityDaySnapshot.UserDay(date, true, observed,
                        orBitmaps(asBytes(results, i * 3 + 1), asBytes(results, i * 3 + 2)), null));
            }
        }
        return collectInOrder(dates, byDate);
    }

    /**
     * 加载帮派在指定日期列表内的日快照，按输入日期顺序返回存在的快照
     *
     * @param factionId 帮派 ID
     * @param dates     查询日期列表（升序）
     * @return 命中数据源的日快照列表，同一日期至多一个
     */
    public List<ActivityDaySnapshot.FactionDay> loadFactionDays(long factionId, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, ActivityDaySnapshot.FactionDay> byDate = HashMap.newHashMap(dates.size());
        for (TornActivityFactionDailyDO row : factionDailyDao.selectByFactionAndDateRange(
                factionId, dates.getFirst(), dates.getLast())) {
            byDate.putIfAbsent(row.getActivityDate(), new ActivityDaySnapshot.FactionDay(
                    row.getActivityDate(), false, row.getObservedBitmap(),
                    row.getActiveCounts(), row.getIdleCounts(), row.getMemberCounts()));
        }

        List<LocalDate> missingV3 = datesNotLoaded(dates, byDate);
        if (!missingV3.isEmpty()) {
            List<Object> results = pipelineGet(buildFactionV3Keys(factionId, missingV3));
            for (int i = 0; i < missingV3.size(); i++) {
                byte[] observed = asBytes(results, i * 4);
                if (observed == null) {
                    continue;
                }
                LocalDate date = missingV3.get(i);
                byDate.put(date, new ActivityDaySnapshot.FactionDay(date, false, observed,
                        asBytes(results, i * 4 + 1), asBytes(results, i * 4 + 2), asBytes(results, i * 4 + 3)));
            }
        }

        List<LocalDate> missingV2 = datesNotLoaded(dates, byDate);
        if (!missingV2.isEmpty()) {
            List<Object> results = pipelineGet(buildFactionV2Keys(factionId, missingV2));
            for (int i = 0; i < missingV2.size(); i++) {
                byte[] observed = asBytes(results, i * 3);
                byte[] onlineCount = asBytes(results, i * 3 + 1);
                byte[] memberCount = asBytes(results, i * 3 + 2);
                if (observed == null || onlineCount == null || memberCount == null) {
                    continue;
                }
                LocalDate date = missingV2.get(i);
                byDate.put(date, new ActivityDaySnapshot.FactionDay(
                        date, true, observed, onlineCount, null, memberCount));
            }
        }
        return collectInOrder(dates, byDate);
    }

    /**
     * 过滤尚未命中快照的日期
     *
     * @param dates  查询日期列表
     * @param byDate 已命中快照的日期映射
     * @return 未命中的日期列表（保持原顺序）
     */
    private static List<LocalDate> datesNotLoaded(List<LocalDate> dates, Map<LocalDate, ?> byDate) {
        return dates.stream().filter(date -> !byDate.containsKey(date)).toList();
    }

    /**
     * 按输入日期顺序收集已命中快照
     *
     * @param dates  查询日期列表
     * @param byDate 已命中快照的日期映射
     * @return 有序快照列表
     */
    private static <T> List<T> collectInOrder(List<LocalDate> dates, Map<LocalDate, T> byDate) {
        return dates.stream().map(byDate::get).filter(Objects::nonNull).toList();
    }

    /**
     * 构建用户 V3 Redis key：每日期 observed/active/idle 三个
     *
     * @param userId 用户 ID
     * @param dates  日期列表
     * @return 与 Pipeline 结果顺序一致的 key 列表
     */
    private static List<String> buildUserV3Keys(long userId, List<LocalDate> dates) {
        List<String> keys = new ArrayList<>(dates.size() * 3);
        for (LocalDate date : dates) {
            keys.add(ActivityRedisKeys.v3UserObserved(userId, date));
            keys.add(ActivityRedisKeys.v3UserActive(userId, date));
            keys.add(ActivityRedisKeys.v3UserIdle(userId, date));
        }
        return keys;
    }

    /**
     * 构建用户 V2 legacy Redis key：每日期 observed/status-active/recent-action 三个
     *
     * @param userId 用户 ID
     * @param dates  日期列表
     * @return 与 Pipeline 结果顺序一致的 key 列表
     */
    private static List<String> buildUserV2Keys(long userId, List<LocalDate> dates) {
        List<String> keys = new ArrayList<>(dates.size() * 3);
        for (LocalDate date : dates) {
            keys.add(ActivityRedisKeys.userObserved(userId, date));
            keys.add(ActivityRedisKeys.userStatusActive(userId, date));
            keys.add(ActivityRedisKeys.userRecentAction(userId, date));
        }
        return keys;
    }

    /**
     * 构建帮派 V3 Redis key：每日期 observed/active/idle/member 四个
     *
     * @param factionId 帮派 ID
     * @param dates     日期列表
     * @return 与 Pipeline 结果顺序一致的 key 列表
     */
    private static List<String> buildFactionV3Keys(long factionId, List<LocalDate> dates) {
        List<String> keys = new ArrayList<>(dates.size() * 4);
        for (LocalDate date : dates) {
            keys.add(ActivityRedisKeys.v3FactionObserved(factionId, date));
            keys.add(ActivityRedisKeys.v3FactionActiveCount(factionId, date));
            keys.add(ActivityRedisKeys.v3FactionIdleCount(factionId, date));
            keys.add(ActivityRedisKeys.v3FactionMemberCount(factionId, date));
        }
        return keys;
    }

    /**
     * 构建帮派 V2 legacy Redis key：每日期 online-count/member-count/observed 三个
     *
     * @param factionId 帮派 ID
     * @param dates     日期列表
     * @return 与 Pipeline 结果顺序一致的 key 列表
     */
    private static List<String> buildFactionV2Keys(long factionId, List<LocalDate> dates) {
        List<String> keys = new ArrayList<>(dates.size() * 3);
        for (LocalDate date : dates) {
            keys.add(ActivityRedisKeys.factionOnlineCount(factionId, date));
            keys.add(ActivityRedisKeys.factionMemberCount(factionId, date));
            keys.add(ActivityRedisKeys.factionObserved(factionId, date));
        }
        return keys;
    }

    /**
     * 单次 Pipeline 批量 GET，结果数量与命令数量强校验
     *
     * @param keys key 列表
     * @return 与 key 顺序一致的结果列表
     */
    private List<Object> pipelineGet(List<String> keys) {
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            for (String key : keys) {
                conn.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }, RedisSerializer.byteArray());
        if (results.size() != keys.size()) {
            throw new IllegalStateException("活跃度查询 Pipeline 结果数量不一致，期望 "
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
     * 两个 Bitmap 按字节 OR 合并（MSB-first 位序下按字节对齐），任一为 null 按空处理
     *
     * @param first  第一个 Bitmap，可为 null
     * @param second 第二个 Bitmap，可为 null
     * @return OR 合并后的 Bitmap
     */
    private static byte[] orBitmaps(byte[] first, byte[] second) {
        int length = Math.max(first == null ? 0 : first.length, second == null ? 0 : second.length);
        byte[] merged = new byte[length];
        for (int i = 0; i < length; i++) {
            int firstByte = first != null && i < first.length ? first[i] : 0;
            int secondByte = second != null && i < second.length ? second[i] : 0;
            merged[i] = (byte) (firstByte | secondByte);
        }
        return merged;
    }
}
