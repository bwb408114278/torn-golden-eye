package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度证据指标计算器 - 从可用15分钟bar计算证据窗口指标、日级对数趋势、负月统计与HIGH/MEDIUM投票。
 * <p>
 * 本类只做纯计算,不访问数据库、不写业务表、不依赖系统时钟;供 {@link StockMonthlyStateCalculator} 复用。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
final class StockMonthlyEvidenceComputer {

    /**
     * 日级趋势最少样本数
     */
    private static final int MIN_TREND_DAILY_CLOSE = 10;
    /**
     * 覆盖率最低要求(95%)
     */
    private static final double MIN_COVERAGE = 0.95;
    /**
     * 最大允许bar间隔(分钟,2小时)
     */
    private static final long MAX_ALLOWED_GAP_MINUTES = 120;

    private StockMonthlyEvidenceComputer() {
    }

    /**
     * 计算证据指标并判定数据完整性。
     *
     * @param evidenceStartTime 证据起点
     * @param evidenceEndTime   证据终点
     * @param usableBars        可用bar列表(按时间升序)
     * @return 证据指标
     */
    static StockMonthlyEvidenceMetrics computeMetrics(LocalDateTime evidenceStartTime,
                                                      LocalDateTime evidenceEndTime,
                                                      List<TornStockMarketBar15mDO> usableBars) {
        List<DailyClose> dailyCloses = dailyCloses(usableBars);
        double evidenceDays = evidenceDays(evidenceStartTime, evidenceEndTime);
        double usableBarCoverage = usableBarCoverage(evidenceStartTime, evidenceEndTime, usableBars.size());
        long maxMissingBucketGap = maxMissingBucketGap(usableBars);
        int dailyCloseCount = dailyCloses.size();

        TrendStats trend = computeTrend(dailyCloses);
        MonthStats monthStats = computeMonthStats(evidenceStartTime, evidenceEndTime, usableBars);

        double fullReturn = fullReturn(dailyCloses);
        double secondHalfReturn = secondHalfReturn(dailyCloses);
        double lastQuarterReturn = lastQuarterReturn(dailyCloses, evidenceEndTime);
        double fullBand = fullBand(dailyCloses);
        double maxDrawdown = maxDrawdown(dailyCloses);

        HighVotes highVotes = highVotes(trend, monthStats, secondHalfReturn, lastQuarterReturn);
        MediumVotes mediumVotes = mediumVotes(trend, monthStats, secondHalfReturn,
                lastQuarterReturn, maxDrawdown);

        boolean complete = usableBarCoverage >= MIN_COVERAGE
                && maxMissingBucketGap <= MAX_ALLOWED_GAP_MINUTES
                && dailyCloseCount >= MIN_TREND_DAILY_CLOSE
                && trend.complete()
                && evidenceDays > 0;
        String incompleteReason = complete ? null : StockMonthlyStateCalculator.REASON_MONTHLY_EVIDENCE_INCOMPLETE;

        boolean quarterTruncated = quarterTruncated(dailyCloses, evidenceEndTime);

        return new StockMonthlyEvidenceMetrics(
                evidenceDays,
                fullReturn,
                annualizedDisplay(dailyCloses, evidenceDays),
                trend.trend30(),
                trend.trend30Low(),
                trend.trend30High(),
                secondHalfReturn,
                lastQuarterReturn,
                fullBand,
                maxDrawdown,
                monthStats.negativeMonthRatio(),
                monthStats.negativeMonthStreak(),
                monthStats.completeMonthCount(),
                usableBarCoverage,
                maxMissingBucketGap,
                dailyCloseCount,
                highVotes.count(),
                mediumVotes.count(),
                highVotes.h1(),
                highVotes.h2(),
                highVotes.h3(),
                highVotes.h4(),
                mediumVotes.m1(),
                mediumVotes.m2(),
                mediumVotes.m3(),
                mediumVotes.m4(),
                mediumVotes.m5(),
                mediumVotes.m6(),
                quarterTruncated,
                complete,
                incompleteReason);
    }

    /**
     * 汇总每日最后一个可用bar价格构成日收盘序列(按日期升序)。
     *
     * @param usableBars 可用bar列表(按时间升序)
     * @return 日收盘序列
     */
    private static List<DailyClose> dailyCloses(List<TornStockMarketBar15mDO> usableBars) {
        Map<LocalDate, Double> byDate = new LinkedHashMap<>();
        for (TornStockMarketBar15mDO bar : usableBars) {
            if (bar == null || bar.getBarStartTime() == null || bar.getLastPrice() == null) {
                continue;
            }
            byDate.put(bar.getBarStartTime().toLocalDate(), bar.getLastPrice().doubleValue());
        }
        List<DailyClose> closes = new ArrayList<>(byDate.size());
        for (Map.Entry<LocalDate, Double> entry : byDate.entrySet()) {
            closes.add(new DailyClose(entry.getKey(), entry.getValue()));
        }
        closes.sort(Comparator.comparing(DailyClose::date));
        return closes;
    }

    /**
     * 计算证据自然日长度(duration/1天)。
     *
     * @param start 证据起点
     * @param end   证据终点
     * @return 自然日长度;任一端为空或区间无效时返回0
     */
    static double evidenceDays(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        return Duration.between(start, end).toMinutes() / 1440.0;
    }

    /**
     * 计算可用bar覆盖率 = 可用bar数 / 期望15分钟桶数。
     *
     * @param start          证据起点
     * @param end            证据终点
     * @param usableBarCount 可用bar数
     * @return 覆盖率(0~1);证据区间无效时返回0
     */
    private static double usableBarCoverage(LocalDateTime start, LocalDateTime end, int usableBarCount) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        long minutes = Duration.between(start, end).toMinutes();
        long expectedBuckets = minutes / 15 + 1;
        if (expectedBuckets <= 0) {
            return 0;
        }
        return (double) usableBarCount / expectedBuckets;
    }

    /**
     * 计算相邻可用bar最大间隔(分钟)。
     *
     * @param usableBars 可用bar列表(按时间升序)
     * @return 最大间隔分钟数;样本不足2个时返回0
     */
    private static long maxMissingBucketGap(List<TornStockMarketBar15mDO> usableBars) {
        long maxGap = 0;
        for (int i = 1; i < usableBars.size(); i++) {
            LocalDateTime prev = usableBars.get(i - 1).getBarStartTime();
            LocalDateTime next = usableBars.get(i).getBarStartTime();
            if (prev == null || next == null) {
                continue;
            }
            long gap = Duration.between(prev, next).toMinutes();
            if (gap > maxGap) {
                maxGap = gap;
            }
        }
        return maxGap;
    }

    /**
     * 计算首尾收益。
     *
     * @param dailyCloses 日收盘序列
     * @return 全窗口收益;样本不足2个或首价为0时返回0
     */
    private static double fullReturn(List<DailyClose> dailyCloses) {
        if (dailyCloses.size() < 2 || dailyCloses.getFirst().price() == 0) {
            return 0;
        }
        return dailyCloses.getLast().price() / dailyCloses.getFirst().price() - 1;
    }

    /**
     * 计算展示年化收益 = (last/first)^(365/evidenceDays) - 1。
     *
     * @param dailyCloses  日收盘序列
     * @param evidenceDays 证据自然日长度
     * @return 展示年化收益;样本不足或天数无效时返回0
     */
    private static double annualizedDisplay(List<DailyClose> dailyCloses, double evidenceDays) {
        if (dailyCloses.size() < 2 || evidenceDays <= 0 || dailyCloses.getFirst().price() == 0) {
            return 0;
        }
        return Math.pow(dailyCloses.getLast().price() / dailyCloses.getFirst().price(),
                365.0 / evidenceDays) - 1;
    }

    /**
     * 计算后半段收益 = last / dailyClose[floor(N/2)] - 1。
     *
     * @param dailyCloses 日收盘序列
     * @return 后半段收益;样本不足2个时返回0
     */
    private static double secondHalfReturn(List<DailyClose> dailyCloses) {
        if (dailyCloses.size() < 2) {
            return 0;
        }
        int anchorIndex = dailyCloses.size() / 2;
        double anchorPrice = dailyCloses.get(anchorIndex).price();
        if (anchorPrice == 0) {
            return 0;
        }
        return dailyCloses.getLast().price() / anchorPrice - 1;
    }

    /**
     * 计算最近季度收益 = last / 最近一个位于或早于(evidenceEnd-90天)的日收盘 - 1。
     * <p>
     * 历史不足90天或证据终点为空时等于全窗口收益。
     *
     * @param dailyCloses 日收盘序列
     * @param evidenceEnd 证据终点
     * @return 最近季度收益
     */
    private static double lastQuarterReturn(List<DailyClose> dailyCloses, LocalDateTime evidenceEnd) {
        if (dailyCloses.isEmpty()) {
            return 0;
        }
        LocalDateTime quarterAnchor = evidenceEnd == null ? null : evidenceEnd.minusDays(90);
        if (quarterAnchor == null) {
            return fullReturn(dailyCloses);
        }
        Double anchorPrice = latestPriceAtOrBefore(dailyCloses, quarterAnchor);
        if (anchorPrice == null || anchorPrice == 0) {
            return fullReturn(dailyCloses);
        }
        return dailyCloses.getLast().price() / anchorPrice - 1;
    }

    /**
     * 取指定时点前最近一个日收盘价格(含该时点所在自然日)。
     *
     * @param dailyCloses 日收盘序列(按日期升序)
     * @param anchorTime  锚定时点
     * @return 该时点前最近日收盘价;无更早样本时返回null
     */
    private static Double latestPriceAtOrBefore(List<DailyClose> dailyCloses, LocalDateTime anchorTime) {
        LocalDate anchorDate = anchorTime.toLocalDate();
        for (int i = dailyCloses.size() - 1; i >= 0; i--) {
            if (!dailyCloses.get(i).date().isAfter(anchorDate)) {
                return dailyCloses.get(i).price();
            }
        }
        return null;
    }

    /**
     * 判断最近季度窗口是否被截断(无早于锚点的日收盘)。
     *
     * @param dailyCloses 日收盘序列
     * @param evidenceEnd 证据终点
     * @return true表示窗口被截断
     */
    private static boolean quarterTruncated(List<DailyClose> dailyCloses, LocalDateTime evidenceEnd) {
        if (evidenceEnd == null || dailyCloses.isEmpty()) {
            return true;
        }
        return latestPriceAtOrBefore(dailyCloses, evidenceEnd.minusDays(90)) == null;
    }

    /**
     * 计算全窗口价格带 = max/min - 1。
     *
     * @param dailyCloses 日收盘序列
     * @return 价格带;样本为空或最小价为0时返回0
     */
    private static double fullBand(List<DailyClose> dailyCloses) {
        if (dailyCloses.isEmpty()) {
            return 0;
        }
        double min = dailyCloses.stream().mapToDouble(DailyClose::price).min().orElse(0);
        double max = dailyCloses.stream().mapToDouble(DailyClose::price).max().orElse(0);
        if (min == 0) {
            return 0;
        }
        return max / min - 1;
    }

    /**
     * 计算最大回撤 = min(price[t]/runningPeak[t] - 1)。
     *
     * @param dailyCloses 日收盘序列
     * @return 最大回撤(负值);样本为空时返回0
     */
    private static double maxDrawdown(List<DailyClose> dailyCloses) {
        double runningPeak = 0;
        double maxDrawdown = 0;
        for (DailyClose dc : dailyCloses) {
            double price = dc.price();
            if (runningPeak == 0) {
                runningPeak = price;
            }
            runningPeak = Math.max(runningPeak, price);
            if (runningPeak > 0) {
                maxDrawdown = Math.min(maxDrawdown, price / runningPeak - 1);
            }
        }
        return maxDrawdown;
    }

    /**
     * 日级对数趋势最小二乘回归结果。
     *
     * @param dailyCloses 日收盘序列(按日期升序)
     * @return 趋势统计(样本不足时complete=false)
     */
    private static TrendStats computeTrend(List<DailyClose> dailyCloses) {
        if (dailyCloses.size() < MIN_TREND_DAILY_CLOSE) {
            return TrendStats.incomplete();
        }
        int n = dailyCloses.size();
        double[] y = new double[n];
        double sumY = 0;
        for (int i = 0; i < n; i++) {
            y[i] = Math.log(dailyCloses.get(i).price());
            sumY += y[i];
        }
        double meanX = (n - 1) / 2.0;
        double meanY = sumY / n;
        double sumX2 = 0;
        double sumXY = 0;
        for (int i = 0; i < n; i++) {
            sumX2 += (i - meanX) * (i - meanX);
            sumXY += (i - meanX) * (y[i] - meanY);
        }
        if (sumX2 == 0) {
            return TrendStats.incomplete();
        }
        double slope = sumXY / sumX2;
        double intercept = meanY - slope * meanX;
        double[] residuals = new double[n];
        double sumResid = 0;
        for (int i = 0; i < n; i++) {
            residuals[i] = y[i] - (intercept + slope * i);
            sumResid += residuals[i];
        }
        double meanResid = sumResid / n;
        double sumResidSq = 0;
        for (double residual : residuals) {
            sumResidSq += (residual - meanResid) * (residual - meanResid);
        }
        double stddevSample = Math.sqrt(sumResidSq / (n - 1));
        double slopeSe = stddevSample / Math.sqrt(sumX2);

        double trend30 = Math.exp(slope * 30) - 1;
        double trend30Low = Math.exp((slope - 1.645 * slopeSe) * 30) - 1;
        double trend30High = Math.exp((slope + 1.645 * slopeSe) * 30) - 1;
        return new TrendStats(true, trend30, trend30Low, trend30High);
    }

    /**
     * 计算完整自然月均价变化与负月统计。
     * <p>
     * 只使用在证据窗口内完整覆盖月初至月末的自然月;月均价为该月全部可用bar
     * lastPrice的算术平均(空bar、空时间、空/非正价格不得参与,不插值)。
     *
     * @param start      证据起点
     * @param end        证据终点
     * @param usableBars 证据窗口内可用bar列表(按时间升序)
     * @return 负月统计
     */
    private static MonthStats computeMonthStats(LocalDateTime start, LocalDateTime end,
                                                List<TornStockMarketBar15mDO> usableBars) {
        if (start == null || end == null) {
            return MonthStats.empty();
        }
        Map<YearMonth, List<Double>> monthPrices = groupMonthPrices(usableBars);
        List<YearMonth> completeMonths = listCompleteMonths(start, end);
        List<Double> monthChanges = computeMonthChanges(monthPrices, completeMonths);
        if (monthChanges.isEmpty()) {
            return new MonthStats(null, 0, completeMonths.size());
        }
        int negativeCount = 0;
        for (Double change : monthChanges) {
            if (change < 0) {
                negativeCount++;
            }
        }
        double negativeRatio = (double) negativeCount / monthChanges.size();
        int streak = trailingNegativeStreak(monthChanges);
        return new MonthStats(negativeRatio, streak, completeMonths.size());
    }

    /**
     * 按自然月收集全部可用bar的lastPrice(算术均值输入)。
     *
     * @param usableBars 可用bar列表
     * @return 月份→有效价格列表
     */
    private static Map<YearMonth, List<Double>> groupMonthPrices(List<TornStockMarketBar15mDO> usableBars) {
        Map<YearMonth, List<Double>> monthPrices = new LinkedHashMap<>();
        for (TornStockMarketBar15mDO bar : usableBars) {
            if (bar == null || bar.getBarStartTime() == null || bar.getLastPrice() == null) {
                continue;
            }
            if (bar.getLastPrice().signum() <= 0) {
                continue;
            }
            YearMonth ym = YearMonth.from(bar.getBarStartTime());
            monthPrices.computeIfAbsent(ym, k -> new ArrayList<>()).add(bar.getLastPrice().doubleValue());
        }
        return monthPrices;
    }

    /**
     * 列出证据窗口内完整覆盖月初至月末的自然月。
     *
     * @param start 证据起点
     * @param end   证据终点
     * @return 完整自然月列表(升序)
     */
    private static List<YearMonth> listCompleteMonths(LocalDateTime start, LocalDateTime end) {
        YearMonth startMonth = YearMonth.from(start);
        YearMonth endMonth = YearMonth.from(end);
        List<YearMonth> completeMonths = new ArrayList<>();
        for (YearMonth ym = startMonth; ym.isBefore(endMonth) || ym.equals(endMonth); ym = ym.plusMonths(1)) {
            LocalDateTime monthStart = ym.atDay(1).atStartOfDay();
            LocalDateTime monthEnd = ym.atEndOfMonth().atTime(23, 59, 59);
            if (!monthStart.isBefore(start) && !monthEnd.isAfter(end)) {
                completeMonths.add(ym);
            }
        }
        return completeMonths;
    }

    /**
     * 计算相邻完整月的均价变化。
     *
     * @param monthPrices    月份→价格列表
     * @param completeMonths 完整自然月列表
     * @return 均价变化序列
     */
    private static List<Double> computeMonthChanges(Map<YearMonth, List<Double>> monthPrices,
                                                    List<YearMonth> completeMonths) {
        List<Double> monthChanges = new ArrayList<>();
        Double prevMean = null;
        for (YearMonth ym : completeMonths) {
            List<Double> prices = monthPrices.getOrDefault(ym, List.of());
            if (prices.isEmpty()) {
                continue;
            }
            double mean = prices.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (prevMean != null && prevMean > 0) {
                monthChanges.add(mean / prevMean - 1);
            }
            prevMean = mean;
        }
        return monthChanges;
    }

    /**
     * 计算末尾连续负月数。
     *
     * @param monthChanges 均价变化序列
     * @return 末尾连续负月数
     */
    private static int trailingNegativeStreak(List<Double> monthChanges) {
        int streak = 0;
        for (int i = monthChanges.size() - 1; i >= 0; i--) {
            if (monthChanges.get(i) < 0) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /**
     * 计算HIGH票。
     *
     * @param trend             趋势统计
     * @param monthStats        负月统计
     * @param secondHalfReturn  后半段收益
     * @param lastQuarterReturn 最近季度收益
     * @return HIGH票结果
     */
    private static HighVotes highVotes(TrendStats trend, MonthStats monthStats,
                                       double secondHalfReturn, double lastQuarterReturn) {
        boolean h1 = trend.trend30High() < -0.003;
        boolean h2 = trend.trend30() < 0 && secondHalfReturn <= -0.015;
        boolean h3 = trend.trend30() < 0 && lastQuarterReturn <= -0.02;
        boolean h4 = monthStats.negativeMonthStreak() >= 3;
        return new HighVotes(h1, h2, h3, h4);
    }

    /**
     * 计算MEDIUM票。
     *
     * @param trend             趋势统计
     * @param monthStats        负月统计
     * @param secondHalfReturn  后半段收益
     * @param lastQuarterReturn 最近季度收益
     * @param maxDrawdown       最大回撤
     * @return MEDIUM票结果
     */
    private static MediumVotes mediumVotes(TrendStats trend, MonthStats monthStats,
                                           double secondHalfReturn, double lastQuarterReturn,
                                           double maxDrawdown) {
        boolean m1 = trend.trend30() < -0.003;
        boolean m2 = secondHalfReturn < -0.008;
        boolean m3 = lastQuarterReturn < -0.012;
        boolean m4 = monthStats.negativeMonthRatio() != null && monthStats.negativeMonthRatio() >= 0.60;
        boolean m5 = monthStats.negativeMonthStreak() >= 2;
        boolean m6 = maxDrawdown <= -0.04;
        return new MediumVotes(m1, m2, m3, m4, m5, m6);
    }

    /**
     * 日收盘样本。
     *
     * @param date  自然日
     * @param price 日末价格
     */
    private record DailyClose(LocalDate date, double price) {
    }

    /**
     * 日级对数趋势结果。
     *
     * @param complete    是否满足最少样本
     * @param trend30     trend30
     * @param trend30Low  trend30下界
     * @param trend30High trend30上界
     */
    private record TrendStats(boolean complete, double trend30, double trend30Low, double trend30High) {
        /**
         * 构造不完整趋势统计。
         *
         * @return 不完整趋势统计
         */
        static TrendStats incomplete() {
            return new TrendStats(false, 0, 0, 0);
        }
    }

    /**
     * 负月统计结果。
     *
     * @param negativeMonthRatio  负月占比(可为null)
     * @param negativeMonthStreak 连续负月数
     * @param completeMonthCount  完整自然月数
     */
    private record MonthStats(Double negativeMonthRatio, int negativeMonthStreak, int completeMonthCount) {
        /**
         * 构造空负月统计。
         *
         * @return 空负月统计
         */
        static MonthStats empty() {
            return new MonthStats(null, 0, 0);
        }
    }

    /**
     * HIGH投票结果。
     *
     * @param h1 H1票
     * @param h2 H2票
     * @param h3 H3票
     * @param h4 H4票
     */
    private record HighVotes(boolean h1, boolean h2, boolean h3, boolean h4) {
        /**
         * 票数。
         *
         * @return 票数
         */
        int count() {
            return (h1 ? 1 : 0) + (h2 ? 1 : 0) + (h3 ? 1 : 0) + (h4 ? 1 : 0);
        }
    }

    /**
     * MEDIUM投票结果。
     *
     * @param m1 M1票
     * @param m2 M2票
     * @param m3 M3票
     * @param m4 M4票
     * @param m5 M5票
     * @param m6 M6票
     */
    private record MediumVotes(boolean m1, boolean m2, boolean m3, boolean m4, boolean m5, boolean m6) {
        /**
         * 票数。
         *
         * @return 票数
         */
        int count() {
            return (m1 ? 1 : 0) + (m2 ? 1 : 0) + (m3 ? 1 : 0)
                    + (m4 ? 1 : 0) + (m5 ? 1 : 0) + (m6 ? 1 : 0);
        }
    }
}
