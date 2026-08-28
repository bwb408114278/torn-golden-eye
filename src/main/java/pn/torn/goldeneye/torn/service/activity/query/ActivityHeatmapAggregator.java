package pn.torn.goldeneye.torn.service.activity.query;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活跃度热力图纯内存矩阵聚合器
 * <p>
 * 只处理已加载的日快照和矩阵计算，不依赖 Spring、Redis、数据库、当前时间或消息对象。
 * 个人/帮派/对比都以 observed 为分母；对比以双方共同原始槽为分母。
 * 所有 Bitmap 按 Redis 的 MSB-first 位序解读。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ActivityHeatmapAggregator {

    private static final int SAMPLES_PER_HOUR = 4;
    private static final int HOURS_PER_DAY = 24;
    private static final int DAYS_PER_WEEK = 7;
    private static final String TO_STRING_LEGACY_INCLUDED = ", legacyIncluded=";
    private static final String TO_STRING_ACTUAL_DAYS = ", actualDays=";
    private static final String TO_STRING_OBSERVED_DOW_COUNT = ", observedDowCount=";

    /**
     * 个人图聚合结果矩阵
     *
     * @param activeRate         7×24 有效活跃比例矩阵（分母为 observed 采样数）
     * @param observedSamples    7×24 有效观测采样数矩阵
     * @param idleRatio          7×24 idle-only 占比矩阵（分母为活跃与 idle 采样数之和，V2 legacy 恒为 0）
     * @param legacyIncluded     是否包含 V2 legacy 快照
     * @param totalObservedSlots 范围内 observed 槽总数
     * @param actualDays         存在 observed 的自然日数量
     * @param observedDowCount   存在 observed 的星期行数量
     */
    public record PersonalMatrix(
            double[][] activeRate,
            int[][] observedSamples,
            double[][] idleRatio,
            boolean legacyIncluded,
            int totalObservedSlots,
            int actualDays,
            int observedDowCount) {

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PersonalMatrix(
                    var thatActiveRate, var thatObservedSamples, var thatIdleRatio,
                    var thatLegacyIncluded, var thatTotalObservedSlots, var thatActualDays,
                    var thatObservedDowCount
            ))) {
                return false;
            }
            return legacyIncluded == thatLegacyIncluded
                    && totalObservedSlots == thatTotalObservedSlots
                    && actualDays == thatActualDays
                    && observedDowCount == thatObservedDowCount
                    && Arrays.deepEquals(activeRate, thatActiveRate)
                    && Arrays.deepEquals(observedSamples, thatObservedSamples)
                    && Arrays.deepEquals(idleRatio, thatIdleRatio);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(legacyIncluded);
            result = 31 * result + totalObservedSlots;
            result = 31 * result + actualDays;
            result = 31 * result + observedDowCount;
            result = 31 * result + Arrays.deepHashCode(activeRate);
            result = 31 * result + Arrays.deepHashCode(observedSamples);
            result = 31 * result + Arrays.deepHashCode(idleRatio);
            return result;
        }

        @Override
        public String toString() {
            return "PersonalMatrix[activeRate=" + Arrays.deepToString(activeRate)
                    + ", observedSamples=" + Arrays.deepToString(observedSamples)
                    + ", idleRatio=" + Arrays.deepToString(idleRatio)
                    + TO_STRING_LEGACY_INCLUDED + legacyIncluded
                    + ", totalObservedSlots=" + totalObservedSlots
                    + TO_STRING_ACTUAL_DAYS + actualDays
                    + TO_STRING_OBSERVED_DOW_COUNT + observedDowCount + "]";
        }
    }

    /**
     * 帮派图聚合结果矩阵
     *
     * @param averageActiveCount 7×24 平均有效活跃人数矩阵（分母为 observed 采样数）
     * @param observedSamples    7×24 有效观测采样数矩阵
     * @param idleRatio          7×24 idle-only 人数占比矩阵（分母为活跃与 idle 人数之和，V2 legacy 恒为 0）
     * @param legacyIncluded     是否包含 V2 legacy 快照
     * @param totalObservedSlots 范围内 observed 槽总数
     * @param actualDays         存在 observed 的自然日数量
     * @param observedDowCount   存在 observed 的星期行数量
     */
    public record FactionMatrix(
            double[][] averageActiveCount,
            int[][] observedSamples,
            double[][] idleRatio,
            boolean legacyIncluded,
            int totalObservedSlots,
            int actualDays,
            int observedDowCount) {

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FactionMatrix(
                    var thatAverageActiveCount, var thatObservedSamples, var thatIdleRatio,
                    var thatLegacyIncluded, var thatTotalObservedSlots, var thatActualDays,
                    var thatObservedDowCount
            ))) {
                return false;
            }
            return legacyIncluded == thatLegacyIncluded
                    && totalObservedSlots == thatTotalObservedSlots
                    && actualDays == thatActualDays
                    && observedDowCount == thatObservedDowCount
                    && Arrays.deepEquals(averageActiveCount, thatAverageActiveCount)
                    && Arrays.deepEquals(observedSamples, thatObservedSamples)
                    && Arrays.deepEquals(idleRatio, thatIdleRatio);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(legacyIncluded);
            result = 31 * result + totalObservedSlots;
            result = 31 * result + actualDays;
            result = 31 * result + observedDowCount;
            result = 31 * result + Arrays.deepHashCode(averageActiveCount);
            result = 31 * result + Arrays.deepHashCode(observedSamples);
            result = 31 * result + Arrays.deepHashCode(idleRatio);
            return result;
        }

        @Override
        public String toString() {
            return "FactionMatrix[averageActiveCount=" + Arrays.deepToString(averageActiveCount)
                    + ", observedSamples=" + Arrays.deepToString(observedSamples)
                    + ", idleRatio=" + Arrays.deepToString(idleRatio)
                    + TO_STRING_LEGACY_INCLUDED + legacyIncluded
                    + ", totalObservedSlots=" + totalObservedSlots
                    + TO_STRING_ACTUAL_DAYS + actualDays
                    + TO_STRING_OBSERVED_DOW_COUNT + observedDowCount + "]";
        }
    }

    /**
     * 对比图聚合结果矩阵
     *
     * @param faction1Average          7×24 帮派A 平均有效活跃人数矩阵（分母为双方共同 observed 槽）
     * @param faction2Average          7×24 帮派B 平均有效活跃人数矩阵
     * @param bothObserved             7×24 共同有效采样标记矩阵
     * @param legacyIncluded           任一方是否包含 V2 legacy 快照
     * @param totalCommonObservedSlots 双方共同 observed 槽总数
     * @param actualDays               存在共同 observed 的自然日数量
     * @param observedDowCount         存在共同 observed 的星期行数量
     */
    public record ComparisonMatrix(
            double[][] faction1Average,
            double[][] faction2Average,
            boolean[][] bothObserved,
            boolean legacyIncluded,
            int totalCommonObservedSlots,
            int actualDays,
            int observedDowCount) {

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ComparisonMatrix(
                    var thatFaction1Average, var thatFaction2Average,
                    var thatBothObserved, var thatLegacyIncluded, var thatTotalCommonObservedSlots,
                    var thatActualDays, var thatObservedDowCount
            ))) {
                return false;
            }
            return legacyIncluded == thatLegacyIncluded
                    && totalCommonObservedSlots == thatTotalCommonObservedSlots
                    && actualDays == thatActualDays
                    && observedDowCount == thatObservedDowCount
                    && Arrays.deepEquals(faction1Average, thatFaction1Average)
                    && Arrays.deepEquals(faction2Average, thatFaction2Average)
                    && Arrays.deepEquals(bothObserved, thatBothObserved);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(legacyIncluded);
            result = 31 * result + totalCommonObservedSlots;
            result = 31 * result + actualDays;
            result = 31 * result + observedDowCount;
            result = 31 * result + Arrays.deepHashCode(faction1Average);
            result = 31 * result + Arrays.deepHashCode(faction2Average);
            result = 31 * result + Arrays.deepHashCode(bothObserved);
            return result;
        }

        @Override
        public String toString() {
            return "ComparisonMatrix[faction1Average=" + Arrays.deepToString(faction1Average)
                    + ", faction2Average=" + Arrays.deepToString(faction2Average)
                    + ", bothObserved=" + Arrays.deepToString(bothObserved)
                    + TO_STRING_LEGACY_INCLUDED + legacyIncluded
                    + ", totalCommonObservedSlots=" + totalCommonObservedSlots
                    + TO_STRING_ACTUAL_DAYS + actualDays
                    + TO_STRING_OBSERVED_DOW_COUNT + observedDowCount + "]";
        }
    }

    /**
     * 聚合个人日快照到星期+小时矩阵
     *
     * @param days 用户日快照列表（同一日期至多一个快照）
     * @return 个人聚合矩阵
     */
    public static PersonalMatrix aggregatePersonal(List<ActivityDaySnapshot.UserDay> days) {
        int[][] observedSum = newIntMatrix();
        double[][] activeSum = newDoubleMatrix();
        double[][] idleSum = newDoubleMatrix();
        boolean[] observedDows = new boolean[DAYS_PER_WEEK];
        int totalObservedSlots = 0;
        int actualDays = 0;
        boolean legacyIncluded = false;

        for (ActivityDaySnapshot.UserDay day : days) {
            int dow = dowIndex(day.date().getDayOfWeek());
            int observedSlotsBefore = totalObservedSlots;
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                int observedCount = countSamples(day.observedBitmap(), hour);
                if (observedCount == 0) {
                    continue;
                }
                observedSum[dow][hour] += observedCount;
                activeSum[dow][hour] += countActiveSamples(day.observedBitmap(), day.activeBitmap(), hour);
                if (!day.legacyV2()) {
                    idleSum[dow][hour] += countActiveSamples(day.observedBitmap(), day.idleBitmap(), hour);
                }
                totalObservedSlots += observedCount;
                observedDows[dow] = true;
            }
            if (totalObservedSlots > observedSlotsBefore) {
                actualDays++;
            }
            legacyIncluded |= day.legacyV2();
        }
        return new PersonalMatrix(buildRate(observedSum, activeSum), observedSum,
                buildIdleRatio(activeSum, idleSum), legacyIncluded,
                totalObservedSlots, actualDays, countTrue(observedDows));
    }

    /**
     * 聚合帮派日快照到星期+小时矩阵
     *
     * @param days 帮派日快照列表（同一日期至多一个快照）
     * @return 帮派聚合矩阵
     */
    public static FactionMatrix aggregateFaction(List<ActivityDaySnapshot.FactionDay> days) {
        double[][] activeSum = newDoubleMatrix();
        double[][] idleSum = newDoubleMatrix();
        int[][] observedCount = newIntMatrix();
        boolean[] observedDows = new boolean[DAYS_PER_WEEK];
        int totalObservedSlots = 0;
        int actualDays = 0;
        boolean legacyIncluded = false;

        for (ActivityDaySnapshot.FactionDay day : days) {
            int dow = dowIndex(day.date().getDayOfWeek());
            int observedSlotsBefore = totalObservedSlots;
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                int slots = countSamples(day.observedBitmap(), hour);
                if (slots == 0) {
                    continue;
                }
                activeSum[dow][hour] += sumObservedSlotValues(day.activeCounts(), day.observedBitmap(), hour);
                if (!day.legacyV2()) {
                    idleSum[dow][hour] += sumObservedSlotValues(day.idleCounts(), day.observedBitmap(), hour);
                }
                observedCount[dow][hour] += slots;
                totalObservedSlots += slots;
                observedDows[dow] = true;
            }
            if (totalObservedSlots > observedSlotsBefore) {
                actualDays++;
            }
            legacyIncluded |= day.legacyV2();
        }
        return new FactionMatrix(buildAverage(activeSum, observedCount), observedCount,
                buildIdleRatio(activeSum, idleSum), legacyIncluded,
                totalObservedSlots, actualDays, countTrue(observedDows));
    }

    /**
     * 聚合双方帮派日快照，仅累计同一日期、同一 15 分钟槽均有观测的共同槽
     *
     * @param faction1Days 帮派A 日快照列表
     * @param faction2Days 帮派B 日快照列表
     * @return 对比聚合矩阵
     */
    public static ComparisonMatrix aggregateComparison(List<ActivityDaySnapshot.FactionDay> faction1Days,
                                                       List<ActivityDaySnapshot.FactionDay> faction2Days) {
        Map<LocalDate, ActivityDaySnapshot.FactionDay> faction2ByDate = new HashMap<>();
        for (ActivityDaySnapshot.FactionDay day : faction2Days) {
            faction2ByDate.put(day.date(), day);
        }

        double[][] faction1Sum = newDoubleMatrix();
        double[][] faction2Sum = newDoubleMatrix();
        int[][] commonCount = newIntMatrix();
        boolean[] observedDows = new boolean[DAYS_PER_WEEK];
        int totalCommonObservedSlots = 0;
        int actualDays = 0;
        boolean legacyIncluded = faction2Days.stream().anyMatch(ActivityDaySnapshot.FactionDay::legacyV2);

        for (ActivityDaySnapshot.FactionDay faction1Day : faction1Days) {
            legacyIncluded |= faction1Day.legacyV2();
            ActivityDaySnapshot.FactionDay faction2Day = faction2ByDate.get(faction1Day.date());
            if (faction2Day == null) {
                continue;
            }
            int dayCommonSlots = accumulateCommonDay(faction1Day, faction2Day,
                    faction1Sum, faction2Sum, commonCount, observedDows);
            if (dayCommonSlots > 0) {
                totalCommonObservedSlots += dayCommonSlots;
                actualDays++;
            }
        }
        return new ComparisonMatrix(buildAverage(faction1Sum, commonCount), buildAverage(faction2Sum, commonCount),
                buildBothObserved(commonCount), legacyIncluded,
                totalCommonObservedSlots, actualDays, countTrue(observedDows));
    }

    /**
     * 累计单个共同日期的双方共同 observed 槽人数
     *
     * @return 该日双方共同 observed 槽总数，0 表示无共同观测
     */
    private static int accumulateCommonDay(ActivityDaySnapshot.FactionDay faction1Day,
                                           ActivityDaySnapshot.FactionDay faction2Day,
                                           double[][] faction1Sum, double[][] faction2Sum,
                                           int[][] commonCount, boolean[] observedDows) {
        int dow = dowIndex(faction1Day.date().getDayOfWeek());
        int dayCommonSlots = 0;
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            int commonSamples = countCommonSamples(faction1Day.observedBitmap(), faction2Day.observedBitmap(), hour);
            if (commonSamples == 0) {
                continue;
            }
            commonCount[dow][hour] += commonSamples;
            faction1Sum[dow][hour] += sumCommonSlotValues(faction1Day.activeCounts(),
                    faction1Day.observedBitmap(), faction2Day.observedBitmap(), hour);
            faction2Sum[dow][hour] += sumCommonSlotValues(faction2Day.activeCounts(),
                    faction1Day.observedBitmap(), faction2Day.observedBitmap(), hour);
            observedDows[dow] = true;
            dayCommonSlots += commonSamples;
        }
        return dayCommonSlots;
    }

    /**
     * 构建活跃比例矩阵（分母为 0 的格保持 0）
     *
     * @return 7×24 比例矩阵
     */
    private static double[][] buildRate(int[][] observedSum, double[][] activeSum) {
        double[][] rate = newDoubleMatrix();
        for (int dow = 0; dow < DAYS_PER_WEEK; dow++) {
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                if (observedSum[dow][hour] > 0) {
                    rate[dow][hour] = Math.clamp(
                            activeSum[dow][hour] / observedSum[dow][hour], 0, 1);
                }
            }
        }
        return rate;
    }

    /**
     * 构建平均值矩阵（分母为 0 的格保持 0）
     *
     * @return 7×24 平均值矩阵
     */
    private static double[][] buildAverage(double[][] sum, int[][] count) {
        double[][] average = newDoubleMatrix();
        for (int dow = 0; dow < DAYS_PER_WEEK; dow++) {
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                if (count[dow][hour] > 0) {
                    average[dow][hour] = sum[dow][hour] / count[dow][hour];
                }
            }
        }
        return average;
    }

    /**
     * 构建 idle 占比矩阵：分母为活跃与 idle 之和，分母为 0 时保持 0
     *
     * @return 7×24 idle 占比矩阵
     */
    private static double[][] buildIdleRatio(double[][] activeSum, double[][] idleSum) {
        double[][] idleRatio = newDoubleMatrix();
        for (int dow = 0; dow < DAYS_PER_WEEK; dow++) {
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                double denominator = activeSum[dow][hour] + idleSum[dow][hour];
                if (denominator > 0) {
                    idleRatio[dow][hour] = Math.clamp(idleSum[dow][hour] / denominator, 0, 1);
                }
            }
        }
        return idleRatio;
    }

    /**
     * 构建共同采样标记矩阵
     *
     * @return 7×24 标记矩阵
     */
    private static boolean[][] buildBothObserved(int[][] commonCount) {
        boolean[][] bothObserved = new boolean[DAYS_PER_WEEK][HOURS_PER_DAY];
        for (int dow = 0; dow < DAYS_PER_WEEK; dow++) {
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                bothObserved[dow][hour] = commonCount[dow][hour] > 0;
            }
        }
        return bothObserved;
    }

    /**
     * 新建 7×24 double 聚合工作矩阵（全 0）
     *
     * @return 空矩阵
     */
    private static double[][] newDoubleMatrix() {
        return new double[DAYS_PER_WEEK][HOURS_PER_DAY];
    }

    /**
     * 新建 7×24 int 聚合工作矩阵（全 0）
     *
     * @return 空矩阵
     */
    private static int[][] newIntMatrix() {
        return new int[DAYS_PER_WEEK][HOURS_PER_DAY];
    }

    /**
     * 将 DayOfWeek 转换为热力图行索引（周一=0，周日=6）
     *
     * @param dow 星期
     * @return 行索引
     */
    static int dowIndex(DayOfWeek dow) {
        return dow == DayOfWeek.SUNDAY ? 6 : dow.getValue() - 1;
    }

    /**
     * 统计布尔数组中的 true 数量
     *
     * @param values 布尔数组
     * @return true 数量
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

    // ==================== Bitmap 位序工具（MSB-first） ====================

    /**
     * 按 Redis Bitmap 的 MSB-first 位序统计指定小时内的置位数
     *
     * @param bitmap Redis Bitmap 原始字节
     * @param hour   小时 (0-23)
     * @return 该小时 4 个槽的置位数
     */
    public static int countSamples(byte[] bitmap, int hour) {
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
     * 统计 observed 与证据 Bitmap 同时置位的槽数（V3 active/idle 采样分母内计数）
     *
     * @param observed observed Bitmap
     * @param evidence 证据 Bitmap（active 或 idle）
     * @param hour     小时 (0-23)
     * @return 同时置位的槽数
     */
    public static int countActiveSamples(byte[] observed, byte[] evidence, int hour) {
        return countBothSetSamples(observed, evidence, hour);
    }

    /**
     * 统计双方 observed Bitmap 在指定小时内的共同采样槽数
     *
     * @param faction1Observed 帮派A observed Bitmap
     * @param faction2Observed 帮派B observed Bitmap
     * @param hour             小时 (0-23)
     * @return 共同采样槽数
     */
    public static int countCommonSamples(byte[] faction1Observed, byte[] faction2Observed, int hour) {
        return countBothSetSamples(faction1Observed, faction2Observed, hour);
    }

    /**
     * 统计指定小时内两个 Bitmap 同时置位的槽数（MSB-first 位序）
     *
     * @param firstBitmap  第一个 Bitmap
     * @param secondBitmap 第二个 Bitmap
     * @param hour         小时 (0-23)
     * @return 同时置位的槽数
     */
    private static int countBothSetSamples(byte[] firstBitmap, byte[] secondBitmap, int hour) {
        int count = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            if (isBitSet(firstBitmap, slot) && isBitSet(secondBitmap, slot)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 仅累计双方共同 observed 槽位中的帮派人数值
     *
     * @param slotData         96 字节槽值
     * @param faction1Observed 帮派A observed Bitmap
     * @param faction2Observed 帮派B observed Bitmap
     * @param hour             小时 (0-23)
     * @return 共同槽计数值之和
     */
    public static int sumCommonSlotValues(byte[] slotData, byte[] faction1Observed,
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
     * 仅累计 observed Bitmap 已置位槽中的帮派人数值
     *
     * @param slotData 96 字节槽值
     * @param observed observed Bitmap
     * @param hour     小时 (0-23)
     * @return observed 槽计数值之和
     */
    public static int sumObservedSlotValues(byte[] slotData, byte[] observed, int hour) {
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
     * 按 Redis MSB-first 位序判断指定槽是否置位
     *
     * @param bitmap Bitmap 原始字节
     * @param slot   槽位 (0-95)
     * @return true 表示置位
     */
    public static boolean isBitSet(byte[] bitmap, int slot) {
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
}
