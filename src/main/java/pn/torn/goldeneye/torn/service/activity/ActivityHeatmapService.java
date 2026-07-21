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

import java.nio.charset.StandardCharsets;
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
    private static final String HEATMAP_TITLE_SUFFIX = "] 活跃度热力图";

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
        validateQueryParameters(userId, days);
        String title = buildPersonalTitle(userId);
        PersonalActivityHeatmapVO vo = PersonalActivityHeatmapVO.empty(title);
        vo.setTotalDays(days);

        List<LocalDate> dates = buildDateRange(days);
        PersonalAggregate agg = loadPersonalAggregate(userId, dates);
        fillPersonalRates(vo, agg);
        fillCoverageAndSufficiency(vo, agg.totalObservedSlots,
                agg.actualDays, agg.observedDowCount(), days);
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
        validateQueryParameters(factionId, days);
        String title = buildFactionTitle(factionId);
        FactionActivityHeatmapVO vo = FactionActivityHeatmapVO.empty(title);
        vo.setSubtitle("格内：平均在线人数｜颜色：在线成员比例｜最近" + days + "天有效采样");
        vo.setTotalDays(days);

        FactionAggregate agg = loadFactionAggregate(factionId, days);
        fillFactionRates(vo, agg);
        fillCoverageAndSufficiency(vo, agg.totalObservedSlots,
                agg.actualDays, agg.observedDowCount(), days);
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
        validateQueryParameters(faction1Id, days);
        validateQueryParameters(faction2Id, days);
        String display1 = buildDisplayName(faction1Id);
        String display2 = buildDisplayName(faction2Id);

        ActivityComparisonHeatmapVO vo = ActivityComparisonHeatmapVO.empty(
                faction1Id, display1, faction2Id, display2);
        vo.setTotalDays(days);

        ComparisonAggregate aggregate = loadComparisonAggregate(faction1Id, faction2Id, days);
        fillComparisonRates(vo, aggregate);
        fillComparisonCoverageAndSufficiency(vo, aggregate.totalCommonObservedSlots,
                aggregate.actualDays, aggregate.observedDowCount(), days);
        return vo;
    }

    // ==================== 个人聚合 ====================

    /**
     * 个人聚合中间结果
     */
    private static final class PersonalAggregate {
        final int[][] observedSum = new int[7][24];
        final int[][] activeSum = new int[7][24];
        final boolean[] observedDows = new boolean[7];
        int totalObservedSlots = 0;
        int actualDays = 0;

        int observedDowCount() {
            return countTrue(observedDows);
        }
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
        validateResultSize("个人 observed", dates.size(), observedBitmaps);
        validateResultSize("个人 status-active", dates.size(), statusActiveBitmaps);
        validateResultSize("个人 recent-action", dates.size(), recentActionBitmaps);
        PersonalAggregate agg = new PersonalAggregate();
        for (int d = 0; d < dates.size(); d++) {
            byte[] observed = observedBitmaps.get(d);
            if (observed == null) {
                continue;
            }
            int observedSlotsBefore = agg.totalObservedSlots;
            byte[] statusActive = statusActiveBitmaps.get(d);
            byte[] recentAction = recentActionBitmaps.get(d);
            int dow = dowIndex(dates.get(d).getDayOfWeek());
            accumulatePersonalHour(agg, observed, statusActive, recentAction, dow);
            if (agg.totalObservedSlots > observedSlotsBefore) {
                agg.actualDays++;
            }
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
            int activeCount = countObservedUnionSamples(observed, statusActive, recentAction, h);
            agg.observedSum[dow][h] += observedCount;
            agg.activeSum[dow][h] += activeCount;
            agg.totalObservedSlots += observedCount;
            agg.observedDows[dow] = true;
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
        final boolean[] observedDows = new boolean[7];
        int totalObservedSlots = 0;
        int actualDays = 0;

        int observedDowCount() {
            return countTrue(observedDows);
        }
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
        validateResultSize("帮派 online-count", dates.size(), onlineCountData);
        validateResultSize("帮派 member-count", dates.size(), memberCountData);
        validateResultSize("帮派 observed", dates.size(), observedBitmaps);
        FactionAggregate agg = new FactionAggregate();
        for (int d = 0; d < dates.size(); d++) {
            byte[] observed = observedBitmaps.get(d);
            byte[] onlineCount = onlineCountData.get(d);
            byte[] memberCount = memberCountData.get(d);
            if (observed == null || onlineCount == null || memberCount == null) {
                continue;
            }
            int observedSlotsBefore = agg.totalObservedSlots;
            int dow = dowIndex(dates.get(d).getDayOfWeek());
            accumulateFactionHour(agg, observed, onlineCount, memberCount, dow);
            if (agg.totalObservedSlots > observedSlotsBefore) {
                agg.actualDays++;
            }
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
            agg.onlineSum[dow][h] += sumObservedSlotValues(onlineCount, observed, h);
            agg.memberSum[dow][h] += sumObservedSlotValues(memberCount, observed, h);
            agg.observedCount[dow][h] += slots;
            agg.totalObservedSlots += slots;
            agg.observedDows[dow] = true;
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
     * 对比聚合中间结果，只累计双方同一日期、同一 15 分钟槽均有观测的数据。
     */
    private static final class ComparisonAggregate {
        final double[][] faction1OnlineSum = new double[7][24];
        final double[][] faction2OnlineSum = new double[7][24];
        final int[][] commonObservedCount = new int[7][24];
        final boolean[] observedDows = new boolean[7];
        int totalCommonObservedSlots;
        int actualDays;

        int observedDowCount() {
            return countTrue(observedDows);
        }
    }

    /**
     * 单次 Pipeline 加载双方帮派人数和观测数据，并按共同槽聚合。
     */
    private ComparisonAggregate loadComparisonAggregate(long faction1Id, long faction2Id, int days) {
        List<LocalDate> dates = buildDateRange(days);
        List<String> keys = new ArrayList<>(dates.size() * 4);
        for (LocalDate date : dates) {
            keys.add(ActivityRedisKeys.factionOnlineCount(faction1Id, date));
            keys.add(ActivityRedisKeys.factionObserved(faction1Id, date));
            keys.add(ActivityRedisKeys.factionOnlineCount(faction2Id, date));
            keys.add(ActivityRedisKeys.factionObserved(faction2Id, date));
        }
        return aggregateComparison(dates, loadRawValues(keys));
    }

    /**
     * 按固定的每日期四项结果顺序聚合双方共同采样槽。
     */
    private ComparisonAggregate aggregateComparison(List<LocalDate> dates, List<byte[]> values) {
        validateResultSize("帮派对比 Pipeline", dates.size() * 4, values);
        ComparisonAggregate aggregate = new ComparisonAggregate();
        for (int dayIndex = 0; dayIndex < dates.size(); dayIndex++) {
            int resultIndex = dayIndex * 4;
            accumulateComparisonDay(aggregate, dates.get(dayIndex),
                    values.get(resultIndex), values.get(resultIndex + 1),
                    values.get(resultIndex + 2), values.get(resultIndex + 3));
        }
        return aggregate;
    }

    /**
     * 累计单日双方共同观测槽。
     */
    private void accumulateComparisonDay(ComparisonAggregate aggregate, LocalDate date,
                                         byte[] faction1Online, byte[] faction1Observed,
                                         byte[] faction2Online, byte[] faction2Observed) {
        if (faction1Online == null || faction1Observed == null
                || faction2Online == null || faction2Observed == null) {
            return;
        }
        int dow = dowIndex(date.getDayOfWeek());
        boolean hasCommonObservation = false;
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            int commonSamples = countCommonSamples(faction1Observed, faction2Observed, hour);
            if (commonSamples > 0) {
                aggregate.commonObservedCount[dow][hour] += commonSamples;
                aggregate.faction1OnlineSum[dow][hour] += sumCommonSlotValues(
                        faction1Online, faction1Observed, faction2Observed, hour);
                aggregate.faction2OnlineSum[dow][hour] += sumCommonSlotValues(
                        faction2Online, faction1Observed, faction2Observed, hour);
                aggregate.totalCommonObservedSlots += commonSamples;
                hasCommonObservation = true;
            }
        }
        if (hasCommonObservation) {
            aggregate.actualDays++;
            aggregate.observedDows[dow] = true;
        }
    }

    /**
     * 填充双方共同采样槽的平均在线人数。
     */
    private void fillComparisonRates(ActivityComparisonHeatmapVO vo, ComparisonAggregate aggregate) {
        for (int dow = 0; dow < 7; dow++) {
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                int commonSamples = aggregate.commonObservedCount[dow][hour];
                if (commonSamples > 0) {
                    vo.getBothObserved()[dow][hour] = true;
                    vo.getFaction1AverageOnline()[dow][hour] =
                            aggregate.faction1OnlineSum[dow][hour] / commonSamples;
                    vo.getFaction2AverageOnline()[dow][hour] =
                            aggregate.faction2OnlineSum[dow][hour] / commonSamples;
                }
            }
        }
    }

    // ==================== 通用填充 ====================

    /**
     * 填充覆盖率和数据充分性到个人 VO
     */
    private void fillCoverageAndSufficiency(
            PersonalActivityHeatmapVO vo, int totalObservedSlots,
            int actualDays, int observedDowCount, int days) {
        vo.setCoverage(calculateCoverage(totalObservedSlots, days));
        vo.setDataSufficient(isDataSufficient(actualDays, observedDowCount));
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage(buildInsufficientMessage(actualDays, observedDowCount));
        }
    }

    /**
     * 填充覆盖率和数据充分性到帮派 VO
     */
    private void fillCoverageAndSufficiency(
            FactionActivityHeatmapVO vo, int totalObservedSlots,
            int actualDays, int observedDowCount, int days) {
        vo.setCoverage(calculateCoverage(totalObservedSlots, days));
        vo.setDataSufficient(isDataSufficient(actualDays, observedDowCount));
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage(buildInsufficientMessage(actualDays, observedDowCount));
        }
    }

    /**
     * 填充对比 VO 的覆盖率和数据充分性。
     *
     * @param vo                       对比热力图 VO
     * @param totalCommonObservedSlots 双方共同观测的 15 分钟槽总数
     * @param actualDays               存在共同观测的自然日数量
     * @param observedDowCount         存在共同观测的星期行数量
     * @param days                     查询天数
     */
    private void fillComparisonCoverageAndSufficiency(
            ActivityComparisonHeatmapVO vo, int totalCommonObservedSlots,
            int actualDays, int observedDowCount, int days) {
        vo.setCoverage(calculateCoverage(totalCommonObservedSlots, days));
        vo.setDataSufficient(isDataSufficient(actualDays, observedDowCount));
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage(buildInsufficientMessage(actualDays, observedDowCount));
        }
    }

    /**
     * 计算覆盖率
     */
    private static double calculateCoverage(int totalObservedSlots, int days) {
        long theoreticalSlots = (long) days * SLOTS_PER_DAY;
        return theoreticalSlots > 0 ? (double) totalObservedSlots / theoreticalSlots : 0;
    }

    /**
     * 判断是否同时满足自然日数量和七个星期行覆盖要求。
     */
    private static boolean isDataSufficient(int actualDays, int observedDowCount) {
        return actualDays >= MIN_DATA_DAYS && observedDowCount == 7;
    }

    /**
     * 统计布尔数组中的 true 数量。
     */
    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    /**
     * 构建数据不足提示信息
     */
    private static String buildInsufficientMessage(int actualDays, int observedDowCount) {
        if (actualDays < MIN_DATA_DAYS) {
            return "⚠️ V2 数据积累中（仅采集 " + actualDays + " 天），需至少 "
                    + MIN_DATA_DAYS + " 天后才能生成有效热力图";
        }
        return "⚠️ V2 数据尚未覆盖周一至周日全部星期行，当前覆盖 " + observedDowCount + "/7";
    }

    /**
     * 校验公共查询入口参数，避免无效 ID 和过大查询窗口造成无意义 Redis 压力。
     */
    private static void validateQueryParameters(long targetId, int days) {
        if (targetId <= 0) {
            throw new IllegalArgumentException("查询目标 ID 必须为正数");
        }
        if (days < MIN_DATA_DAYS || days > 30) {
            throw new IllegalArgumentException("查询天数必须在 " + MIN_DATA_DAYS + " 到 30 天之间");
        }
    }

    /**
     * 构建个人热力图标题
     */
    private String buildPersonalTitle(long userId) {
        String userName = getUserName(userId);
        return userName != null
                ? userName + " [" + userId + HEATMAP_TITLE_SUFFIX
                : "用户 [" + userId + HEATMAP_TITLE_SUFFIX;
    }

    /**
     * 构建帮派热力图标题
     */
    private String buildFactionTitle(long factionId) {
        String factionName = getFactionName(factionId);
        return factionName != null
                ? factionName + " [" + factionId + HEATMAP_TITLE_SUFFIX
                : "帮派 [" + factionId + HEATMAP_TITLE_SUFFIX;
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
                conn.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }, RedisSerializer.byteArray());
        return mapPipelineResults(results);
    }

    /**
     * 将 Pipeline 结果列表转为 byte[] 列表，null 保留占位
     */
    static List<byte[]> mapPipelineResults(List<Object> results) {
        if (results == null) {
            throw new IllegalStateException("Redis Pipeline 未返回结果列表");
        }
        return results.stream()
                .map(result -> result instanceof byte[] bytes ? bytes : null)
                .toList();
    }

    /**
     * 校验 Pipeline 结果数量与命令数量一致，避免结果错位后静默污染聚合。
     */
    private static void validateResultSize(String dataName, int expectedSize, List<byte[]> values) {
        if (values == null || values.size() != expectedSize) {
            int actualSize = values == null ? -1 : values.size();
            throw new IllegalStateException(dataName + " 结果数量不一致，期望 "
                    + expectedSize + "，实际 " + actualSize);
        }
    }

    // ==================== 名称查询 ====================

    /**
     * 从 Redis 名称缓存获取用户名，不存在返回 null
     */
    private String getUserName(long userId) {
        Object name = redisTemplate.opsForHash().get(
                ActivityRedisKeys.USER_NAME_CACHE_KEY, ActivityRedisKeys.userNameField(userId));
        return name != null ? name.toString() : null;
    }

    /**
     * 从 Redis 名称缓存获取帮派名，不存在返回 null
     */
    private String getFactionName(long factionId) {
        Object name = redisTemplate.opsForHash().get(
                ActivityRedisKeys.FACTION_NAME_CACHE_KEY, ActivityRedisKeys.factionNameField(factionId));
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
     * 按槽位 OR 统计双证据 Bitmap 的活跃采样数。
     */
    static int countObservedUnionSamples(byte[] observed, byte[] statusActive,
                                         byte[] recentAction, int hour) {
        int count = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            if (isBitSet(observed, slot)
                    && (isBitSet(statusActive, slot) || isBitSet(recentAction, slot))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计双方 observed Bitmap 在指定小时内的共同采样槽数。
     */
    static int countCommonSamples(byte[] faction1Observed, byte[] faction2Observed, int hour) {
        int count = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            if (isBitSet(faction1Observed, slot) && isBitSet(faction2Observed, slot)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 仅累计双方共同 observed 槽位中的帮派人数值。
     */
    static int sumCommonSlotValues(byte[] slotData, byte[] faction1Observed,
                                   byte[] faction2Observed, int hour) {
        if (slotData == null) {
            return 0;
        }
        int sum = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            if (slot < slotData.length
                    && isBitSet(faction1Observed, slot)
                    && isBitSet(faction2Observed, slot)) {
                sum += slotData[slot] & 0xFF;
            }
        }
        return sum;
    }

    /**
     * 仅累计 observed Bitmap 已置位槽中的帮派人数值。
     */
    static int sumObservedSlotValues(byte[] slotData, byte[] observed, int hour) {
        if (slotData == null) {
            return 0;
        }
        int sum = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            if (slot < slotData.length && isBitSet(observed, slot)) {
                sum += slotData[slot] & 0xFF;
            }
        }
        return sum;
    }

    /**
     * 按 Redis MSB-first 位序判断指定槽是否置位。
     */
    private static boolean isBitSet(byte[] bitmap, int slot) {
        if (bitmap == null) {
            return false;
        }
        int byteIndex = slot / Byte.SIZE;
        if (byteIndex >= bitmap.length) {
            return false;
        }
        int mask = 1 << (Byte.SIZE - 1 - slot % Byte.SIZE);
        return (bitmap[byteIndex] & 0xFF & mask) != 0;
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
