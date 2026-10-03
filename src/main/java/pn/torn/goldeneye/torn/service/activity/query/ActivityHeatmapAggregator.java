package pn.torn.goldeneye.torn.service.activity.query;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.model.activity.grid.ActivityGridLayout;
import pn.torn.goldeneye.torn.model.activity.grid.WeekdayHourGridLayout;

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
 * @version 1.7.0
 * @since 2026.08.28
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ActivityHeatmapAggregator {

    /**
     * 每小时的采样槽数
     */
    private static final int SAMPLES_PER_HOUR = 4;
    /**
     * 每天的小时数
     */
    private static final int HOURS_PER_DAY = 24;
    /**
     * 每天的采样槽数
     */
    private static final int SLOTS_PER_DAY = HOURS_PER_DAY * SAMPLES_PER_HOUR;
    private static final String TO_STRING_LEGACY_INCLUDED = ", legacyIncluded=";
    private static final String TO_STRING_ACTUAL_DAYS = ", actualDays=";
    private static final String TO_STRING_OBSERVED_ROW_COUNT = ", observedRowCount=";
    private static final String TO_STRING_OBSERVED_DOW_COUNT = ", observedDowCount=";

    /**
     * 个人图聚合结果矩阵
     *
     * @param activeRate         有效活跃比例矩阵（维度由口径网格决定，分母为 observed 采样数）
     * @param observedSamples    有效观测采样数矩阵
     * @param idleRatio          idle-only 占比矩阵（分母为活跃与 idle 采样数之和，V2 legacy 恒为 0）
     * @param legacyIncluded     是否包含 V2 legacy 快照
     * @param totalObservedSlots 范围内 observed 槽总数
     * @param actualDays         存在 observed 的自然日数量
     * @param observedRowCount   存在 observed 的网格行数量
     */
    public record PersonalMatrix(
            double[][] activeRate,
            int[][] observedSamples,
            double[][] idleRatio,
            boolean legacyIncluded,
            int totalObservedSlots,
            int actualDays,
            int observedRowCount) {

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PersonalMatrix(
                    var thatActiveRate, var thatObservedSamples, var thatIdleRatio,
                    var thatLegacyIncluded, var thatTotalObservedSlots, var thatActualDays,
                    var thatObservedRowCount
            ))) {
                return false;
            }
            return legacyIncluded == thatLegacyIncluded
                    && totalObservedSlots == thatTotalObservedSlots
                    && actualDays == thatActualDays
                    && observedRowCount == thatObservedRowCount
                    && Arrays.deepEquals(activeRate, thatActiveRate)
                    && Arrays.deepEquals(observedSamples, thatObservedSamples)
                    && Arrays.deepEquals(idleRatio, thatIdleRatio);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(legacyIncluded);
            result = 31 * result + totalObservedSlots;
            result = 31 * result + actualDays;
            result = 31 * result + observedRowCount;
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
                    + TO_STRING_OBSERVED_ROW_COUNT + observedRowCount + "]";
        }
    }

    /**
     * 帮派图聚合结果矩阵
     *
     * @param averageActiveCount 平均有效活跃人数矩阵（维度由口径网格决定，分母为 observed 采样数）
     * @param observedSamples    有效观测采样数矩阵
     * @param idleRatio          idle-only 人数占比矩阵（分母为活跃与 idle 人数之和，V2 legacy 恒为 0）
     * @param legacyIncluded     是否包含 V2 legacy 快照
     * @param totalObservedSlots 范围内 observed 槽总数
     * @param actualDays         存在 observed 的自然日数量
     * @param observedRowCount   存在 observed 的网格行数量
     */
    public record FactionMatrix(
            double[][] averageActiveCount,
            int[][] observedSamples,
            double[][] idleRatio,
            boolean legacyIncluded,
            int totalObservedSlots,
            int actualDays,
            int observedRowCount) {

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FactionMatrix(
                    var thatAverageActiveCount, var thatObservedSamples, var thatIdleRatio,
                    var thatLegacyIncluded, var thatTotalObservedSlots, var thatActualDays,
                    var thatObservedRowCount
            ))) {
                return false;
            }
            return legacyIncluded == thatLegacyIncluded
                    && totalObservedSlots == thatTotalObservedSlots
                    && actualDays == thatActualDays
                    && observedRowCount == thatObservedRowCount
                    && Arrays.deepEquals(averageActiveCount, thatAverageActiveCount)
                    && Arrays.deepEquals(observedSamples, thatObservedSamples)
                    && Arrays.deepEquals(idleRatio, thatIdleRatio);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(legacyIncluded);
            result = 31 * result + totalObservedSlots;
            result = 31 * result + actualDays;
            result = 31 * result + observedRowCount;
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
                    + TO_STRING_OBSERVED_ROW_COUNT + observedRowCount + "]";
        }
    }

    /**
     * 对比图聚合结果矩阵
     *
     * @param faction1Average          帮派A 平均有效活跃人数矩阵（分母为双方共同 observed 槽）
     * @param faction2Average          帮派B 平均有效活跃人数矩阵
     * @param bothObserved             共同有效采样标记矩阵
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
     * 已观测槽的落格消费者：遍历只写一份，各图只提供"怎么取分子"。
     *
     * @param <D> 日快照类型
     */
    @FunctionalInterface
    interface SlotConsumer<D extends ActivityDaySnapshot> {
        /**
         * 处理一个已观测槽。
         *
         * @param day  槽所属日快照
         * @param slot 采样槽序号（0-95）
         * @param row  落格行号
         * @param col  落格列号
         */
        void accept(D day, int slot, int row, int col);
    }

    /**
     * 按槽遍历所有已观测槽，落点由口径网格决定。
     *
     * @param days     日快照列表（同一日期至多一个快照）
     * @param grid     口径网格
     * @param consumer 已观测槽的落格消费者
     * @param <D>      日快照类型
     */
    static <D extends ActivityDaySnapshot> void forEachObservedSlot(
            List<D> days, ActivityGridLayout grid, SlotConsumer<D> consumer) {
        for (D day : days) {
            for (int slot = 0; slot < SLOTS_PER_DAY; slot++) {
                if (isBitSet(day.observedBitmap(), slot)) {
                    consumer.accept(day, slot, grid.rowOf(day.date(), slot), grid.colOf(slot));
                }
            }
        }
    }

    /**
     * 聚合个人日快照到口径网格矩阵
     *
     * @param days 用户日快照列表（同一日期至多一个快照）
     * @param grid 口径网格
     * @return 个人聚合矩阵
     */
    public static PersonalMatrix aggregatePersonal(List<ActivityDaySnapshot.UserDay> days,
                                                   ActivityGridLayout grid) {
        int[][] observedSum = newIntMatrix(grid);
        double[][] activeSum = newDoubleMatrix(grid);
        double[][] idleSum = newDoubleMatrix(grid);
        boolean[] observedRows = new boolean[grid.rows()];

        forEachObservedSlot(days, grid, (day, slot, row, col) -> {
            observedSum[row][col]++;
            observedRows[row] = true;
            if (isBitSet(day.activeBitmap(), slot)) {
                activeSum[row][col]++;
            }
            if (!day.legacyV2() && isBitSet(day.idleBitmap(), slot)) {
                idleSum[row][col]++;
            }
        });

        return new PersonalMatrix(buildRate(observedSum, activeSum), observedSum,
                buildIdleRatio(activeSum, idleSum), isLegacyIncluded(days),
                sumCells(observedSum), countDaysWithObservedSlot(days), countTrue(observedRows));
    }

    /**
     * 聚合帮派日快照到口径网格矩阵
     *
     * @param days 帮派日快照列表（同一日期至多一个快照）
     * @param grid 口径网格
     * @return 帮派聚合矩阵
     */
    public static FactionMatrix aggregateFaction(List<ActivityDaySnapshot.FactionDay> days,
                                                 ActivityGridLayout grid) {
        double[][] activeSum = newDoubleMatrix(grid);
        double[][] idleSum = newDoubleMatrix(grid);
        int[][] observedCount = newIntMatrix(grid);
        boolean[] observedRows = new boolean[grid.rows()];

        forEachObservedSlot(days, grid, (day, slot, row, col) -> {
            observedCount[row][col]++;
            observedRows[row] = true;
            activeSum[row][col] += slotValue(day.activeCounts(), slot);
            if (!day.legacyV2()) {
                idleSum[row][col] += slotValue(day.idleCounts(), slot);
            }
        });

        return new FactionMatrix(buildAverage(activeSum, observedCount), observedCount,
                buildIdleRatio(activeSum, idleSum), isLegacyIncluded(days),
                sumCells(observedCount), countDaysWithObservedSlot(days), countTrue(observedRows));
    }

    /**
     * 聚合双方帮派日快照，仅累计同一日期、同一 15 分钟槽均有观测的共同槽
     * <p>
     * 对比图只有星期+小时一种视图，矩阵维度与星期行下标都取
     * {@link WeekdayHourGridLayout#INSTANCE}，不需要第二套 7×24 常量。
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

        ActivityGridLayout grid = WeekdayHourGridLayout.INSTANCE;
        double[][] faction1Sum = newDoubleMatrix(grid);
        double[][] faction2Sum = newDoubleMatrix(grid);
        int[][] commonCount = newIntMatrix(grid);
        boolean[] observedDows = new boolean[grid.rows()];
        int totalCommonObservedSlots = 0;
        int actualDays = 0;
        boolean legacyIncluded = isLegacyIncluded(faction2Days);

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
        int dow = WeekdayHourGridLayout.INSTANCE.rowOf(faction1Day.date());
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
     * 构建活跃比例矩阵（按数组自身长度遍历，分母为 0 的格保持 0）
     *
     * @return 与输入同维度的比例矩阵
     */
    private static double[][] buildRate(int[][] observedSum, double[][] activeSum) {
        double[][] rate = new double[observedSum.length][observedSum[0].length];
        for (int row = 0; row < observedSum.length; row++) {
            for (int col = 0; col < observedSum[row].length; col++) {
                if (observedSum[row][col] > 0) {
                    rate[row][col] = Math.clamp(
                            activeSum[row][col] / observedSum[row][col], 0, 1);
                }
            }
        }
        return rate;
    }

    /**
     * 构建平均值矩阵（按数组自身长度遍历，分母为 0 的格保持 0）
     *
     * @return 与输入同维度的平均值矩阵
     */
    private static double[][] buildAverage(double[][] sum, int[][] count) {
        double[][] average = new double[sum.length][sum[0].length];
        for (int row = 0; row < sum.length; row++) {
            for (int col = 0; col < sum[row].length; col++) {
                if (count[row][col] > 0) {
                    average[row][col] = sum[row][col] / count[row][col];
                }
            }
        }
        return average;
    }

    /**
     * 构建 idle 占比矩阵：分母为活跃与 idle 之和，分母为 0 时保持 0
     *
     * @return 与输入同维度的 idle 占比矩阵
     */
    private static double[][] buildIdleRatio(double[][] activeSum, double[][] idleSum) {
        double[][] idleRatio = new double[activeSum.length][activeSum[0].length];
        for (int row = 0; row < activeSum.length; row++) {
            for (int col = 0; col < activeSum[row].length; col++) {
                double denominator = activeSum[row][col] + idleSum[row][col];
                if (denominator > 0) {
                    idleRatio[row][col] = Math.clamp(idleSum[row][col] / denominator, 0, 1);
                }
            }
        }
        return idleRatio;
    }

    /**
     * 构建共同采样标记矩阵
     *
     * @return 与输入同维度的标记矩阵
     */
    private static boolean[][] buildBothObserved(int[][] commonCount) {
        boolean[][] bothObserved = new boolean[commonCount.length][commonCount[0].length];
        for (int row = 0; row < commonCount.length; row++) {
            for (int col = 0; col < commonCount[row].length; col++) {
                bothObserved[row][col] = commonCount[row][col] > 0;
            }
        }
        return bothObserved;
    }

    /**
     * 新建与网格同维度的 double 聚合工作矩阵（全 0）
     *
     * @param grid 口径网格
     * @return 空矩阵
     */
    private static double[][] newDoubleMatrix(ActivityGridLayout grid) {
        return new double[grid.rows()][grid.cols()];
    }

    /**
     * 新建与网格同维度的 int 聚合工作矩阵（全 0）
     *
     * @param grid 口径网格
     * @return 空矩阵
     */
    private static int[][] newIntMatrix(ActivityGridLayout grid) {
        return new int[grid.rows()][grid.cols()];
    }

    /**
     * 判断快照集合是否包含 V2 legacy 采样
     *
     * @param days 日快照列表
     * @return true 表示至少一个 legacy 快照
     */
    private static boolean isLegacyIncluded(List<? extends ActivityDaySnapshot> days) {
        return days.stream().anyMatch(ActivityDaySnapshot::legacyV2);
    }

    /**
     * 统计存在至少一个 observed 槽的自然日数量
     *
     * @param days 日快照列表
     * @return 有效采样自然日数量
     */
    private static int countDaysWithObservedSlot(List<? extends ActivityDaySnapshot> days) {
        int count = 0;
        for (ActivityDaySnapshot day : days) {
            if (hasObservedSlot(day.observedBitmap())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断 96 个采样槽中是否存在已置位的 observed 槽
     *
     * @param observedBitmap observed Bitmap
     * @return true 表示至少一个槽已观测
     */
    private static boolean hasObservedSlot(byte[] observedBitmap) {
        for (int slot = 0; slot < SLOTS_PER_DAY; slot++) {
            if (isBitSet(observedBitmap, slot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 累加矩阵全部格值
     *
     * @param counts 计数矩阵
     * @return 格值总和
     */
    private static int sumCells(int[][] counts) {
        int sum = 0;
        for (int[] row : counts) {
            for (int value : row) {
                sum += value;
            }
        }
        return sum;
    }

    /**
     * 读取 observed 已置位槽中的人数槽值，数组缺失或越界按 0 处理
     *
     * @param counts 96 字节人数槽值，可为 null
     * @param slot   采样槽序号（0-95）
     * @return 该槽计数
     */
    private static int slotValue(byte[] counts, int slot) {
        return counts == null || slot >= counts.length ? 0 : counts[slot] & 0xFF;
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
     * 统计双方 observed Bitmap 在指定小时内同时置位的共同采样槽数（MSB-first 位序）
     *
     * @param faction1Observed 帮派A observed Bitmap
     * @param faction2Observed 帮派B observed Bitmap
     * @param hour             小时 (0-23)
     * @return 共同采样槽数
     */
    public static int countCommonSamples(byte[] faction1Observed, byte[] faction2Observed, int hour) {
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
