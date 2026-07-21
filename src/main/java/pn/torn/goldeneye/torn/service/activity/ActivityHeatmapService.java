package pn.torn.goldeneye.torn.service.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 活跃度热力图查询服务
 * <p>
 * 只读 Redis，单次 Pipeline 批量获取 V2 key，JVM 内做 Bitmap OR 和矩阵聚合。
 * 不调用 Torn API，不按成员/日期/小时逐条同步查询 Redis，帮派查询命令数不随当前成员数增长。
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityHeatmapService {
    private static final int SAMPLES_PER_HOUR = 4;
    private static final int HOURS_PER_DAY = 24;
    private static final int SLOTS_PER_DAY = 96;
    static final int MIN_DATA_DAYS = 7;

    private final StringRedisTemplate redisTemplate;

    // ==================== 个人热力图 ====================

    /**
     * 查询个人活跃度热力图
     * <p>
     * Pipeline 批量读取 28 天的 observed / status-active / recent-action 三组 Bitmap，
     * JVM 内对两个活跃 Bitmap 做 OR，按星期几+小时聚合为活跃比例。
     *
     * @param userId 用户 ID
     * @param days   查询天数
     * @return 个人热力图数据
     */
    public PersonalActivityHeatmapVO queryPersonalHeatmap(long userId, int days) {
        String title = buildPersonalTitle(userId);
        PersonalActivityHeatmapVO vo = PersonalActivityHeatmapVO.empty(title);
        vo.setTotalDays(days);

        List<LocalDate> dates = buildDateRange(days);
        PersonalAggregate agg = loadPersonalAggregate(userId, dates);
        fillPersonalRates(vo, agg);
        fillCoverageAndSufficiency(vo, agg.totalObservedSlots, agg.actualDays, days);
        return vo;
    }

    // ==================== 帮派热力图 ====================

    /**
     * 查询帮派活跃度热力图（平均在线人数）
     * <p>
     * Pipeline 批量读取 28 天的帮派历史快照（online-count / member-count / observed），
     * 不依赖当前成员 Set，查询命令数不随成员数增长。
     *
     * @param factionId 帮派 ID
     * @param days      查询天数
     * @return 帮派热力图数据
     */
    public FactionActivityHeatmapVO queryFactionHeatmap(long factionId, int days) {
        String title = buildFactionTitle(factionId);
        FactionActivityHeatmapVO vo = FactionActivityHeatmapVO.empty(title);
        vo.setSubtitle("格内：平均在线人数｜颜色：在线成员比例｜最近" + days + "天有效采样");
        vo.setTotalDays(days);

        FactionAggregate agg = loadFactionAggregate(factionId, days);
        fillFactionRates(vo, agg);
        fillCoverageAndSufficiency(vo, agg.totalObservedSlots, agg.actualDays, days);
        return vo;
    }

    // ==================== 帮派对比 ====================

    /**
     * 对比两个帮派的活跃度
     * <p>
     * 同一个格子只有在双方都有有效观测时才进行对比。
     *
     * @param faction1Id 帮派A ID
     * @param faction2Id 帮派B ID
     * @param days       查询天数
     * @return 对比热力图数据
     */
    public ActivityComparisonHeatmapVO compareFactions(long faction1Id, long faction2Id, int days) {
        String display1 = buildDisplayName(faction1Id);
        String display2 = buildDisplayName(faction2Id);

        ActivityComparisonHeatmapVO vo = ActivityComparisonHeatmapVO.empty(
                faction1Id, display1, faction2Id, display2);
        vo.setTotalDays(days);

        FactionAggregate agg1 = loadFactionAggregate(faction1Id, days);
        FactionAggregate agg2 = loadFactionAggregate(faction2Id, days);

        int totalBothObserved = fillComparisonRates(vo, agg1, agg2);
        int actualDays = Math.min(agg1.actualDays, agg2.actualDays);
        String worseName = agg1.actualDays <= agg2.actualDays ? display1 : display2;
        fillComparisonCoverageAndSufficiency(vo, totalBothObserved, actualDays,
                days, worseName, actualDays);
        return vo;
    }

    // ==================== 个人聚合 ====================

    /**
     * 个人聚合中间结果
     */
    private static final class PersonalAggregate {
        final int[][] observedSum = new int[7][24];
        final int[][] activeSum = new int[7][24];
        int totalObservedSlots = 0;
        int actualDays = 0;
    }

    /**
     * 加载并聚合个人 Bitmap 数据
     */
    private PersonalAggregate loadPersonalAggregate(long userId, List<LocalDate> dates) {
        List<byte[]> observedBitmaps = loadBitmaps(
                dates.stream().map(d -> ActivityRedisKeys.userObserved(userId, d)).toList());
        List<byte[]> statusActiveBitmaps = loadBitmaps(
                dates.stream().map(d -> ActivityRedisKeys.userStatusActive(userId, d)).toList());
        List<byte[]> recentActionBitmaps = loadBitmaps(
                dates.stream().map(d -> ActivityRedisKeys.userRecentAction(userId, d)).toList());
        return aggregatePersonal(dates, observedBitmaps, statusActiveBitmaps, recentActionBitmaps);
    }

    /**
     * 聚合个人 Bitmap 数据到星期+小时矩阵
     */
    private PersonalAggregate aggregatePersonal(List<LocalDate> dates,
                                                 List<byte[]> observedBitmaps,
                                                 List<byte[]> statusActiveBitmaps,
                                                 List<byte[]> recentActionBitmaps) {
        PersonalAggregate agg = new PersonalAggregate();
        for (int d = 0; d < dates.size(); d++) {
            byte[] observed = observedBitmaps.get(d);
            if (observed == null) {
                continue;
            }
            agg.actualDays++;
            byte[] statusActive = statusActiveBitmaps.get(d);
            byte[] recentAction = recentActionBitmaps.get(d);
            int dow = dowIndex(dates.get(d).getDayOfWeek());
            accumulatePersonalHour(agg, observed, statusActive, recentAction, dow);
        }
        return agg;
    }

    /**
     * 累加个人每小时的观测和活跃采样数
     */
    private void accumulatePersonalHour(PersonalAggregate agg, byte[] observed,
                                         byte[] statusActive, byte[] recentAction, int dow) {
        for (int h = 0; h < HOURS_PER_DAY; h++) {
            int observedCount = countSamples(observed, h);
            if (observedCount == 0) {
                continue;
            }
            int activeCount = countSamples(statusActive, h) + countSamples(recentAction, h);
            activeCount = Math.min(activeCount, observedCount);
            agg.observedSum[dow][h] += observedCount;
            agg.activeSum[dow][h] += activeCount;
            agg.totalObservedSlots += observedCount;
        }
    }

    /**
     * 填充个人活跃比例和观测采样数到 VO
     */
    private void fillPersonalRates(PersonalActivityHeatmapVO vo, PersonalAggregate agg) {
        for (int dow = 0; dow < 7; dow++) {
            for (int h = 0; h < HOURS_PER_DAY; h++) {
                vo.getObservedSamples()[dow][h] = agg.observedSum[dow][h];
                if (agg.observedSum[dow][h] > 0) {
                    double rate = (double) agg.activeSum[dow][h] / agg.observedSum[dow][h];
                    vo.getActiveRate()[dow][h] = Math.clamp(rate, 0, 1);
                }
            }
        }
    }

    // ==================== 帮派聚合 ====================

    /**
     * 帮派聚合中间结果
     */
    private static final class FactionAggregate {
        final double[][] onlineSum = new double[7][24];
        final double[][] memberSum = new double[7][24];
        final int[][] observedCount = new int[7][24];
        int totalObservedSlots = 0;
        int actualDays = 0;
    }

    /**
     * 加载帮派聚合中间数据
     */
    private FactionAggregate loadFactionAggregate(long factionId, int days) {
        List<LocalDate> dates = buildDateRange(days);
        List<byte[]> onlineCountData = loadRawValues(
                dates.stream().map(d -> ActivityRedisKeys.factionOnlineCount(factionId, d)).toList());
        List<byte[]> memberCountData = loadRawValues(
                dates.stream().map(d -> ActivityRedisKeys.factionMemberCount(factionId, d)).toList());
        List<byte[]> observedBitmaps = loadBitmaps(
                dates.stream().map(d -> ActivityRedisKeys.factionObserved(factionId, d)).toList());
        return aggregateFaction(dates, onlineCountData, memberCountData, observedBitmaps);
    }

    /**
     * 聚合帮派 Bitmap 和槽数据到星期+小时矩阵
     */
    private FactionAggregate aggregateFaction(List<LocalDate> dates,
                                                List<byte[]> onlineCountData,
                                                List<byte[]> memberCountData,
                                                List<byte[]> observedBitmaps) {
        FactionAggregate agg = new FactionAggregate();
        for (int d = 0; d < dates.size(); d++) {
            byte[] observed = observedBitmaps.get(d);
            byte[] onlineCount = onlineCountData.get(d);
            byte[] memberCount = memberCountData.get(d);
            if (observed == null || onlineCount == null || memberCount == null) {
                continue;
            }
            agg.actualDays++;
            int dow = dowIndex(dates.get(d).getDayOfWeek());
            accumulateFactionHour(agg, observed, onlineCount, memberCount, dow);
        }
        return agg;
    }

    /**
     * 累加帮派每小时的在线人数和成员数
     */
    private void accumulateFactionHour(FactionAggregate agg, byte[] observed,
                                        byte[] onlineCount, byte[] memberCount, int dow) {
        for (int h = 0; h < HOURS_PER_DAY; h++) {
            int slots = countSamples(observed, h);
            if (slots == 0) {
                continue;
            }
            agg.onlineSum[dow][h] += sumSlotValues(onlineCount, h);
            agg.memberSum[dow][h] += sumSlotValues(memberCount, h);
            agg.observedCount[dow][h] += slots;
            agg.totalObservedSlots += slots;
        }
    }

    /**
     * 填充帮派平均在线人数和在线比例到 VO
     */
    private void fillFactionRates(FactionActivityHeatmapVO vo, FactionAggregate agg) {
        for (int dow = 0; dow < 7; dow++) {
            for (int h = 0; h < HOURS_PER_DAY; h++) {
                vo.getObservedSamples()[dow][h] = agg.observedCount[dow][h];
                if (agg.observedCount[dow][h] > 0) {
                    vo.getAverageOnlineCount()[dow][h] = agg.onlineSum[dow][h] / agg.observedCount[dow][h];
                    if (agg.memberSum[dow][h] > 0) {
                        vo.getOnlineRatio()[dow][h] = Math.clamp(
                                agg.onlineSum[dow][h] / agg.memberSum[dow][h], 0, 1);
                    }
                }
            }
        }
    }

    // ==================== 对比聚合 ====================

    /**
     * 填充对比双方平均在线人数到 VO，返回双方均有观测的格子总数
     */
    private int fillComparisonRates(ActivityComparisonHeatmapVO vo, FactionAggregate agg1, FactionAggregate agg2) {
        int totalBothObserved = 0;
        for (int dow = 0; dow < 7; dow++) {
            for (int h = 0; h < HOURS_PER_DAY; h++) {
                if (agg1.observedCount[dow][h] > 0 && agg2.observedCount[dow][h] > 0) {
                    vo.getBothObserved()[dow][h] = true;
                    vo.getFaction1AverageOnline()[dow][h] = agg1.onlineSum[dow][h] / agg1.observedCount[dow][h];
                    vo.getFaction2AverageOnline()[dow][h] = agg2.onlineSum[dow][h] / agg2.observedCount[dow][h];
                    totalBothObserved++;
                }
            }
        }
        return totalBothObserved;
    }

    // ==================== 通用填充 ====================

    /**
     * 填充覆盖率和数据充分性到个人 VO
     */
    private void fillCoverageAndSufficiency(
            PersonalActivityHeatmapVO vo, int totalObservedSlots, int actualDays, int days) {
        vo.setCoverage(calculateCoverage(totalObservedSlots, days));
        vo.setDataSufficient(actualDays >= MIN_DATA_DAYS);
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage(buildInsufficientMessage(actualDays));
        }
    }

    /**
     * 填充覆盖率和数据充分性到帮派 VO
     */
    private void fillCoverageAndSufficiency(
            FactionActivityHeatmapVO vo, int totalObservedSlots, int actualDays, int days) {
        vo.setCoverage(calculateCoverage(totalObservedSlots, days));
        vo.setDataSufficient(actualDays >= MIN_DATA_DAYS);
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage(buildInsufficientMessage(actualDays));
        }
    }

    /**
     * 填充对比 VO 的覆盖率和数据充分性
     *
     * @param vo                对比热力图 VO
     * @param totalBothObserved 双方均有观测的格子总数
     * @param actualDays        双方中较少的采集天数
     * @param days              查询天数
     * @param worseName         采集天数较少一方的显示名称
     * @param worseDays         采集天数较少一方的天数
     */
    private void fillComparisonCoverageAndSufficiency(
            ActivityComparisonHeatmapVO vo, int totalBothObserved, int actualDays,
            int days, String worseName, int worseDays) {
        vo.setCoverage(calculateCoverage(totalBothObserved, days));
        vo.setDataSufficient(actualDays >= MIN_DATA_DAYS);
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage("⚠️ V2 数据积累中，" + worseName + " 仅采集 " + worseDays
                    + " 天，需至少 " + MIN_DATA_DAYS + " 天");
        }
    }

    /**
     * 计算覆盖率
     */
    private static double calculateCoverage(int totalObservedSlots, int days) {
        int theoreticalSlots = days * SLOTS_PER_DAY;
        return theoreticalSlots > 0 ? (double) totalObservedSlots / theoreticalSlots : 0;
    }

    /**
     * 构建数据不足提示信息
     */
    private static String buildInsufficientMessage(int actualDays) {
        return "⚠️ V2 数据积累中（仅采集 " + actualDays + " 天），需至少 "
                + MIN_DATA_DAYS + " 天后才能生成有效热力图";
    }

    /**
     * 构建个人热力图标题
     */
    private String buildPersonalTitle(long userId) {
        String userName = getUserName(userId);
        return (userName != null ? userName : String.valueOf(userId))
                + " [" + userId + "] 活跃度热力图";
    }

    /**
     * 构建帮派热力图标题
     */
    private String buildFactionTitle(long factionId) {
        String factionName = getFactionName(factionId);
        return (factionName != null ? factionName : String.valueOf(factionId))
                + " [" + factionId + "] 活跃度热力图";
    }

    /**
     * 构建显示名称：有名称时 "名称 [ID]"，无名称时仅 ID
     */
    private String buildDisplayName(long factionId) {
        String name = getFactionName(factionId);
        return name != null ? name + " [" + factionId + "]" : String.valueOf(factionId);
    }

    // ==================== Redis 批量读取 ====================

    /**
     * Pipeline 批量读取 Bitmap 值
     */
    private List<byte[]> loadBitmaps(List<String> keys) {
        return loadRawValues(keys);
    }

    /**
     * Pipeline 批量读取原始 byte[] 值
     */
    private List<byte[]> loadRawValues(List<String> keys) {
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            for (String key : keys) {
                conn.stringCommands().get(key.getBytes());
            }
            return null;
        }, RedisSerializer.byteArray());
        return mapPipelineResults(results);
    }

    /**
     * 将 Pipeline 结果列表转为 byte[] 列表，null 保留占位
     */
    static List<byte[]> mapPipelineResults(List<Object> results) {
        return results.stream()
                .map(result -> result instanceof byte[] bytes ? bytes : null)
                .toList();
    }

    // ==================== 名称查询 ====================

    /**
     * 从 Redis 名称缓存获取用户名，不存在返回 null
     */
    private String getUserName(long userId) {
        Object name = redisTemplate.opsForHash().get(
                ActivityRedisKeys.USER_NAMES_HASH, ActivityRedisKeys.userNameField(userId));
        return name != null ? name.toString() : null;
    }

    /**
     * 从 Redis 名称缓存获取帮派名，不存在返回 null
     */
    private String getFactionName(long factionId) {
        Object name = redisTemplate.opsForHash().get(
                ActivityRedisKeys.FACTION_NAMES_HASH, ActivityRedisKeys.factionNameField(factionId));
        return name != null ? name.toString() : null;
    }

    // ==================== Bitmap 解析工具 ====================

    /**
     * 按 Redis Bitmap 的 MSB-first 位序统计指定小时内的置位数
     *
     * @param bitmap Redis Bitmap 原始字节
     * @param hour   小时 (0-23)
     * @return 该小时 4 个槽的置位数
     */
    static int countSamples(byte[] bitmap, int hour) {
        if (bitmap == null) {
            return 0;
        }
        int count = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            int byteIndex = slot / Byte.SIZE;
            if (byteIndex < bitmap.length
                    && (bitmap[byteIndex] & 0xFF & (1 << (Byte.SIZE - 1 - slot % Byte.SIZE))) != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 从帮派槽数据中累加指定小时 4 个槽的计数值
     *
     * @param slotData 96 字节槽数据，每槽 1 字节
     * @param hour     小时 (0-23)
     * @return 该小时 4 个槽的计数值之和
     */
    static int sumSlotValues(byte[] slotData, int hour) {
        if (slotData == null) {
            return 0;
        }
        int sum = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            if (slot < slotData.length) {
                sum += slotData[slot] & 0xFF;
            }
        }
        return sum;
    }

    // ==================== 日期工具 ====================

    /**
     * 构建从今天向前的日期列表（今天在前）
     */
    private static List<LocalDate> buildDateRange(int days) {
        LocalDate today = LocalDate.now(TornActivityCollectService.HEATMAP_ZONE);
        List<LocalDate> dates = new ArrayList<>(days);
        for (int d = 0; d < days; d++) {
            dates.add(today.minusDays(d));
        }
        return dates;
    }

    /**
     * 将 DayOfWeek 转换为热力图行索引（周一=0，周日=6）
     */
    private static int dowIndex(DayOfWeek dow) {
        return dow == DayOfWeek.SUNDAY ? 6 : dow.getValue() - 1;
    }
}
