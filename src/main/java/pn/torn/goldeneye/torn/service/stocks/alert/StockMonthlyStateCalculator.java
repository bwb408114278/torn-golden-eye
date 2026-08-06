package pn.torn.goldeneye.torn.service.stocks.alert;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度状态纯计算器 - 按冻结公式 {@code PERSONALITY_RULE_V1} 与 {@code RISK_RULE_V1_SHADOW}
 * 从可用15分钟bar计算月度证据指标、成熟度、六类原始风格、风险投票、迟滞建议与有效风险。
 * <p>
 * 本类只做纯计算与状态判定,不访问数据库、不写业务表、不依赖系统时钟,便于领域测试与回放复用。
 * 冻结规则版本:
 * <ul>
 *   <li>风格规则版本: {@value #PERSONALITY_RULE_VERSION}</li>
 *   <li>风险规则版本: {@value #RISK_RULE_VERSION}</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@Component
public class StockMonthlyStateCalculator {

    /**
     * 人格分类规则版本(冻结)
     */
    public static final String PERSONALITY_RULE_VERSION = "PERSONALITY_RULE_V1";
    /**
     * 风险分级规则版本(冻结)
     */
    public static final String RISK_RULE_VERSION = "RISK_RULE_V1_SHADOW";

    /**
     * 不完整原因: 证据数据不完整
     */
    public static final String REASON_MONTHLY_EVIDENCE_INCOMPLETE = "MONTHLY_EVIDENCE_INCOMPLETE";
    /**
     * 不完整原因: 历史快照缺少迟滞所需raw字段
     */
    public static final String REASON_PREVIOUS_RAW_MISSING = "PREVIOUS_RAW_MISSING";

    /**
     * 证据窗口最大回溯天数
     */
    static final int MAX_EVIDENCE_DAYS = 365;
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

    // ==================== 公开入口 ====================

    /**
     * 计算单支股票的月度状态草稿。
     *
     * @param stocksId          股票ID
     * @param stocksShortname   股票简称快照
     * @param effectiveMonth    目标生效月份(当月1日)
     * @param evidenceStartTime 证据区间起始时间
     * @param evidenceEndTime   证据区间结束时间
     * @param usableBars        证据窗口内可用bar列表(按时间升序)
     * @param previous          上一确认月份状态(无历史时为null)
     * @return 月度状态计算结果
     */
    public StockMonthlyStateDraft calculate(Integer stocksId,
                                            String stocksShortname,
                                            LocalDate effectiveMonth,
                                            LocalDateTime evidenceStartTime,
                                            LocalDateTime evidenceEndTime,
                                            List<TornStockMarketBar15mDO> usableBars,
                                            StockMonthlyPrevious previous) {
        List<DailyClose> dailyCloses = dailyCloses(usableBars);
        StockMonthlyEvidenceMetrics metrics = computeMetrics(evidenceStartTime, evidenceEndTime,
                usableBars, dailyCloses);
        StockMaturityEnum maturity = determineMaturity(evidenceStartTime, evidenceEndTime);
        if (!metrics.complete()) {
            return buildIncompleteDraft(stocksId, stocksShortname, effectiveMonth,
                    evidenceStartTime, evidenceEndTime, maturity, metrics);
        }

        StockStrategyFitEnum rawPersonality = classifyRawPersonality(metrics);
        StockRiskLevelEnum rawRiskLevel = computeRawRiskLevel(metrics);
        PersonalityResolution personality = applyPersonalityHysteresis(
                rawPersonality, previous, metrics);
        RiskResolution risk = applyRiskHysteresis(rawRiskLevel, previous);

        boolean confirmable = personality.confirmable() && risk.confirmable();
        String metricSnapshot = buildMetricSnapshot(metrics, rawPersonality, rawRiskLevel,
                personality.suggested(), risk.riskLevel(), personality.reason());

        return new StockMonthlyStateDraft(
                stocksId, stocksShortname, effectiveMonth,
                evidenceStartTime, evidenceEndTime,
                maturity, rawPersonality,
                previous == null ? null : previous.previousPersonality(),
                personality.suggested(), personality.suggested(),
                rawRiskLevel, risk.riskLevel(),
                metricSnapshot, true, null, confirmable,
                personality.reason());
    }

    /**
     * 解析上一确认月度状态为迟滞参考。
     *
     * @param previousDO 上一确认月度状态(可为null)
     * @return 迟滞参考;无历史时为null
     */
    public StockMonthlyPrevious parsePrevious(TornStockMonthlyStateDO previousDO) {
        if (previousDO == null) {
            return null;
        }
        Map<String, Object> rawMap = parseSnapshot(previousDO.getMetricSnapshot());
        return new StockMonthlyPrevious(
                parseStyle(previousDO.getStrategyFitPrior()),
                parseRisk(previousDO.getRiskLevel()),
                parseStyle(asString(rawMap, "rawPersonality")),
                parseRisk(asString(rawMap, "rawRiskLevel")));
    }

    // ==================== 证据指标计算 ====================

    /**
     * 计算证据指标并判定数据完整性。
     *
     * @param evidenceStartTime 证据起点
     * @param evidenceEndTime   证据终点
     * @param usableBars        可用bar列表(按时间升序)
     * @param dailyCloses       日收盘序列
     * @return 证据指标
     */
    private StockMonthlyEvidenceMetrics computeMetrics(LocalDateTime evidenceStartTime,
                                                       LocalDateTime evidenceEndTime,
                                                       List<TornStockMarketBar15mDO> usableBars,
                                                       List<DailyClose> dailyCloses) {
        double evidenceDays = evidenceDays(evidenceStartTime, evidenceEndTime);
        double usableBarCoverage = usableBarCoverage(evidenceStartTime, evidenceEndTime, usableBars.size());
        long maxMissingBucketGap = maxMissingBucketGap(usableBars);
        int dailyCloseCount = dailyCloses.size();

        TrendStats trend = computeTrend(dailyCloses);
        MonthStats monthStats = computeMonthStats(evidenceStartTime, evidenceEndTime, dailyCloses);

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
        String incompleteReason = complete ? null : REASON_MONTHLY_EVIDENCE_INCOMPLETE;

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
    private List<DailyClose> dailyCloses(List<TornStockMarketBar15mDO> usableBars) {
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
        closes.sort(java.util.Comparator.comparing(DailyClose::date));
        return closes;
    }

    /**
     * 计算证据自然日长度(duration/1天)。
     *
     * @param start 证据起点
     * @param end   证据终点
     * @return 自然日长度;任一端为空或区间无效时返回0
     */
    private double evidenceDays(LocalDateTime start, LocalDateTime end) {
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
    private double usableBarCoverage(LocalDateTime start, LocalDateTime end, int usableBarCount) {
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
    private long maxMissingBucketGap(List<TornStockMarketBar15mDO> usableBars) {
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

    // ==================== 指标公式 ====================

    /**
     * 计算首尾收益。
     *
     * @param dailyCloses 日收盘序列
     * @return 全窗口收益;样本不足2个或首价为0时返回0
     */
    private double fullReturn(List<DailyClose> dailyCloses) {
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
    private double annualizedDisplay(List<DailyClose> dailyCloses, double evidenceDays) {
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
    private double secondHalfReturn(List<DailyClose> dailyCloses) {
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
    private double lastQuarterReturn(List<DailyClose> dailyCloses, LocalDateTime evidenceEnd) {
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
    private Double latestPriceAtOrBefore(List<DailyClose> dailyCloses, LocalDateTime anchorTime) {
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
    private boolean quarterTruncated(List<DailyClose> dailyCloses, LocalDateTime evidenceEnd) {
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
    private double fullBand(List<DailyClose> dailyCloses) {
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
    private double maxDrawdown(List<DailyClose> dailyCloses) {
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
    private TrendStats computeTrend(List<DailyClose> dailyCloses) {
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
     * lastPrice的算术平均。
     *
     * @param start       证据起点
     * @param end         证据终点
     * @param dailyCloses 日收盘序列(用于月份分组,价格取日收盘)
     * @return 负月统计
     */
    private MonthStats computeMonthStats(LocalDateTime start, LocalDateTime end,
                                         List<DailyClose> dailyCloses) {
        Map<YearMonth, List<Double>> monthPrices = new LinkedHashMap<>();
        for (DailyClose dc : dailyCloses) {
            YearMonth ym = YearMonth.from(dc.date());
            monthPrices.computeIfAbsent(ym, k -> new ArrayList<>()).add(dc.price());
        }
        if (start == null || end == null) {
            return MonthStats.empty();
        }
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
        int streak = 0;
        for (int i = monthChanges.size() - 1; i >= 0; i--) {
            if (monthChanges.get(i) < 0) {
                streak++;
            } else {
                break;
            }
        }
        return new MonthStats(negativeRatio, streak, completeMonths.size());
    }

    // ==================== 投票计算 ====================

    /**
     * 计算HIGH票。
     *
     * @param trend             趋势统计
     * @param monthStats        负月统计
     * @param secondHalfReturn  后半段收益
     * @param lastQuarterReturn 最近季度收益
     * @return HIGH票结果
     */
    private HighVotes highVotes(TrendStats trend, MonthStats monthStats,
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
    private MediumVotes mediumVotes(TrendStats trend, MonthStats monthStats,
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

    // ==================== 分类 ====================

    /**
     * 成熟度按证据自然日分级(60/120/240/365)。
     *
     * @param start 证据起点
     * @param end   证据终点
     * @return 成熟度枚举
     */
    private StockMaturityEnum determineMaturity(LocalDateTime start, LocalDateTime end) {
        double days = evidenceDays(start, end);
        if (days < 60) {
            return StockMaturityEnum.M0_UNMATURE;
        }
        if (days < 120) {
            return StockMaturityEnum.M1_EARLY;
        }
        if (days < 240) {
            return StockMaturityEnum.M2_PROVISIONAL;
        }
        if (days < 365) {
            return StockMaturityEnum.M3_SEASONED;
        }
        return StockMaturityEnum.M4_MATURE;
    }

    /**
     * 六类原始风格按 DECLINER → WEAK → NARROW → RANGING → STRONG → STEADY 首次命中。
     *
     * @param metrics 证据指标
     * @return 原始风格
     */
    private StockStrategyFitEnum classifyRawPersonality(StockMonthlyEvidenceMetrics metrics) {
        double annualized = metrics.annualizedDisplay();
        double trend30 = metrics.trend30();
        double secondHalf = metrics.secondHalfReturn();
        double lastQuarter = metrics.lastQuarterReturn();
        double fullBand = metrics.fullBand();
        int streak = metrics.negativeMonthStreak();

        boolean decliner = (annualized <= -0.08 && trend30 <= -0.006 && secondHalf <= -0.01)
                || (streak >= 3 && lastQuarter <= -0.015);
        if (decliner) {
            return StockStrategyFitEnum.DECLINER;
        }

        boolean weak = (annualized <= -0.025 && trend30 <= -0.0025)
                || (metrics.negativeMonthRatio() != null && metrics.negativeMonthRatio() >= 0.60 && secondHalf < 0)
                || (secondHalf <= -0.025 && trend30 < 0);
        if (weak) {
            return StockStrategyFitEnum.WEAK;
        }

        boolean narrow = fullBand <= 0.045 && Math.abs(annualized) <= 0.05 && Math.abs(trend30) <= 0.004;
        if (narrow) {
            return StockStrategyFitEnum.NARROW;
        }

        boolean ranging = fullBand <= 0.10 && Math.abs(annualized) <= 0.07 && Math.abs(trend30) <= 0.006;
        if (ranging) {
            return StockStrategyFitEnum.RANGING;
        }

        boolean strong = annualized >= 0.08 && trend30 >= 0.006 && secondHalf >= 0;
        if (strong) {
            return StockStrategyFitEnum.STRONG;
        }
        return StockStrategyFitEnum.STEADY;
    }

    /**
     * 计算原始风险等级。
     *
     * @param metrics 证据指标
     * @return 原始风险
     */
    private StockRiskLevelEnum computeRawRiskLevel(StockMonthlyEvidenceMetrics metrics) {
        boolean highOverride = metrics.trend30High() < -0.006 && metrics.lastQuarterReturn() < 0;
        if (metrics.highVotes() >= 2 || highOverride) {
            return StockRiskLevelEnum.HIGH;
        }
        if (metrics.mediumVotes() >= 2) {
            return StockRiskLevelEnum.MEDIUM;
        }
        return StockRiskLevelEnum.NONE;
    }

    // ==================== 迟滞 ====================

    /**
     * 风格迟滞:立即生效、NARROW↔RANGING两月、恢复两月与显著越界。
     *
     * @param raw      当月原始风格
     * @param previous 上一确认月份
     * @param metrics  当月证据指标
     * @return 建议风格与迟滞原因
     */
    private PersonalityResolution applyPersonalityHysteresis(StockStrategyFitEnum raw,
                                                             StockMonthlyPrevious previous,
                                                             StockMonthlyEvidenceMetrics metrics) {
        if (previous == null) {
            return new PersonalityResolution(raw, true, "NO_PREVIOUS_IMMEDIATE");
        }
        StockStrategyFitEnum prev = previous.previousPersonality();
        StockStrategyFitEnum prevRaw = previous.previousRawPersonality();
        if (raw == StockStrategyFitEnum.DECLINER || raw == StockStrategyFitEnum.WEAK) {
            return new PersonalityResolution(raw, true, "RISK_UPGRADE_IMMEDIATE");
        }
        if (raw == prev) {
            return new PersonalityResolution(raw, true, "SAME_AS_PREVIOUS");
        }
        if ((prev == StockStrategyFitEnum.STEADY && raw == StockStrategyFitEnum.STRONG)
                || (prev == StockStrategyFitEnum.STRONG && raw == StockStrategyFitEnum.STEADY)) {
            return new PersonalityResolution(raw, true, "STRONG_STEADY_DIRECT");
        }
        if ((prev == StockStrategyFitEnum.NARROW && raw == StockStrategyFitEnum.RANGING)
                || (prev == StockStrategyFitEnum.RANGING && raw == StockStrategyFitEnum.NARROW)) {
            return resolveNarrowRanging(prev, raw, prevRaw, metrics);
        }
        if (isRecoveryTransition(prev, raw)) {
            return resolveRecovery(prev, raw, prevRaw);
        }
        return new PersonalityResolution(raw, true, "OTHER_DIRECT");
    }

    /**
     * 判断是否为需要两月确认的恢复/降档转换。
     *
     * @param prev 上一确认风格
     * @param raw  当月原始风格
     * @return true表示需要两月确认
     */
    private boolean isRecoveryTransition(StockStrategyFitEnum prev, StockStrategyFitEnum raw) {
        boolean fromRisk = prev == StockStrategyFitEnum.DECLINER || prev == StockStrategyFitEnum.WEAK;
        boolean toSafe = raw == StockStrategyFitEnum.NARROW
                || raw == StockStrategyFitEnum.RANGING
                || raw == StockStrategyFitEnum.STEADY
                || raw == StockStrategyFitEnum.STRONG;
        boolean fromStrong = prev == StockStrategyFitEnum.STRONG;
        boolean toLower = raw == StockStrategyFitEnum.NARROW
                || raw == StockStrategyFitEnum.RANGING
                || raw == StockStrategyFitEnum.STEADY;
        return (fromRisk && toSafe) || (fromStrong && toLower);
    }

    /**
     * NARROW↔RANGING迟滞:显著越界当月切换,否则连续两月raw均为目标才切换。
     *
     * @param prev    上一确认风格
     * @param raw     当月原始风格
     * @param prevRaw 上一月raw风格(可能缺失)
     * @param metrics 当月证据指标
     * @return 建议风格与迟滞原因
     */
    private PersonalityResolution resolveNarrowRanging(StockStrategyFitEnum prev,
                                                       StockStrategyFitEnum raw,
                                                       StockStrategyFitEnum prevRaw,
                                                       StockMonthlyEvidenceMetrics metrics) {
        if (isSignificantOverrun(raw, metrics)) {
            return new PersonalityResolution(raw, true, "NARROW_RANGING_SIGNIFICANT_OVERRUN");
        }
        if (prevRaw == null) {
            return new PersonalityResolution(prev, false, REASON_PREVIOUS_RAW_MISSING);
        }
        if (prevRaw == raw) {
            return new PersonalityResolution(raw, true, "NARROW_RANGING_TWO_MONTH");
        }
        return new PersonalityResolution(prev, true, "NARROW_RANGING_HOLD");
    }

    /**
     * 显著越界判断(当月切换)。
     *
     * @param raw     目标风格
     * @param metrics 当月证据指标
     * @return true表示显著越界
     */
    private boolean isSignificantOverrun(StockStrategyFitEnum raw,
                                         StockMonthlyEvidenceMetrics metrics) {
        double fullBand = metrics.fullBand();
        double annualized = metrics.annualizedDisplay();
        double trend30 = metrics.trend30();
        if (raw == StockStrategyFitEnum.RANGING) {
            return fullBand > 0.055 || Math.abs(annualized) > 0.06 || Math.abs(trend30) > 0.005;
        }
        return fullBand <= 0.035 && Math.abs(annualized) <= 0.04 && Math.abs(trend30) <= 0.003;
    }

    /**
     * 恢复/降档迟滞:连续两月raw均为目标才切换,否则保留上一风格。
     *
     * @param prev    上一确认风格
     * @param raw     当月原始风格
     * @param prevRaw 上一月raw风格(可能缺失)
     * @return 建议风格与迟滞原因
     */
    private PersonalityResolution resolveRecovery(StockStrategyFitEnum prev,
                                                  StockStrategyFitEnum raw,
                                                  StockStrategyFitEnum prevRaw) {
        if (prevRaw == null) {
            return new PersonalityResolution(prev, false, REASON_PREVIOUS_RAW_MISSING);
        }
        if (prevRaw == raw) {
            return new PersonalityResolution(raw, true, "RECOVERY_TWO_MONTH");
        }
        return new PersonalityResolution(prev, true, "RECOVERY_HOLD");
    }

    /**
     * 风险迟滞:HIGH立即生效;MEDIUM遇上一HIGH保持;NONE需连续两月raw NONE才解除。
     *
     * @param rawRisk  当月原始风险
     * @param previous 上一确认月份
     * @return 有效风险与可确认标记
     */
    private RiskResolution applyRiskHysteresis(StockRiskLevelEnum rawRisk,
                                               StockMonthlyPrevious previous) {
        if (rawRisk == StockRiskLevelEnum.HIGH) {
            return new RiskResolution(StockRiskLevelEnum.HIGH, true);
        }
        if (previous == null) {
            return new RiskResolution(rawRisk, true);
        }
        if (rawRisk == StockRiskLevelEnum.MEDIUM) {
            if (previous.previousRiskLevel() == StockRiskLevelEnum.HIGH) {
                return new RiskResolution(StockRiskLevelEnum.HIGH, true);
            }
            return new RiskResolution(StockRiskLevelEnum.MEDIUM, true);
        }
        StockRiskLevelEnum prevRisk = previous.previousRiskLevel();
        if (prevRisk == StockRiskLevelEnum.HIGH || prevRisk == StockRiskLevelEnum.MEDIUM) {
            if (previous.previousRawRiskLevel() == null) {
                return new RiskResolution(prevRisk, false);
            }
            if (previous.previousRawRiskLevel() == StockRiskLevelEnum.NONE) {
                return new RiskResolution(StockRiskLevelEnum.NONE, true);
            }
            return new RiskResolution(prevRisk, true);
        }
        return new RiskResolution(StockRiskLevelEnum.NONE, true);
    }

    // ==================== 快照与结果组装 ====================

    /**
     * 组装指标快照JSON。
     *
     * @param metrics             证据指标
     * @param rawPersonality      原始风格
     * @param rawRiskLevel        原始风险
     * @param suggestedPersonality 建议风格
     * @param riskLevel           有效风险
     * @param hysteresisReason    迟滞原因
     * @return 指标快照JSON文本
     */
    private String buildMetricSnapshot(StockMonthlyEvidenceMetrics metrics,
                                       StockStrategyFitEnum rawPersonality,
                                       StockRiskLevelEnum rawRiskLevel,
                                       StockStrategyFitEnum suggestedPersonality,
                                       StockRiskLevelEnum riskLevel,
                                       String hysteresisReason) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("rawPersonality", rawPersonality == null ? null : rawPersonality.getCode());
        snapshot.put("rawRiskLevel", rawRiskLevel == null ? null : rawRiskLevel.getCode());
        snapshot.put("suggestedPersonality", suggestedPersonality == null ? null : suggestedPersonality.getCode());
        snapshot.put("riskLevel", riskLevel == null ? null : riskLevel.getCode());
        snapshot.put("annualizedDisplay", metrics.annualizedDisplay());
        snapshot.put("trend30", metrics.trend30());
        snapshot.put("trend30Low", metrics.trend30Low());
        snapshot.put("trend30High", metrics.trend30High());
        snapshot.put("secondHalfReturn", metrics.secondHalfReturn());
        snapshot.put("lastQuarterReturn", metrics.lastQuarterReturn());
        snapshot.put("fullBand", metrics.fullBand());
        snapshot.put("maxDrawdown", metrics.maxDrawdown());
        snapshot.put("negativeMonthRatio", metrics.negativeMonthRatio());
        snapshot.put("negativeMonthStreak", metrics.negativeMonthStreak());
        snapshot.put("highVotes", metrics.highVotes());
        snapshot.put("mediumVotes", metrics.mediumVotes());
        snapshot.put("h1", metrics.h1());
        snapshot.put("h2", metrics.h2());
        snapshot.put("h3", metrics.h3());
        snapshot.put("h4", metrics.h4());
        snapshot.put("m1", metrics.m1());
        snapshot.put("m2", metrics.m2());
        snapshot.put("m3", metrics.m3());
        snapshot.put("m4", metrics.m4());
        snapshot.put("m5", metrics.m5());
        snapshot.put("m6", metrics.m6());
        snapshot.put("usableBarCoverage", metrics.usableBarCoverage());
        snapshot.put("maxMissingBucketGap", metrics.maxMissingBucketGap());
        snapshot.put("evidenceDays", metrics.evidenceDays());
        snapshot.put("completeMonthCount", metrics.completeMonthCount());
        snapshot.put("quarterWindowTruncated", metrics.quarterWindowTruncated());
        snapshot.put("hysteresisReason", hysteresisReason);
        snapshot.put("incompleteReason", metrics.incompleteReason());
        return JsonUtils.objToJson(snapshot);
    }

    /**
     * 构建不完整草稿(证据不足时保持DRAFT且风险/风格为空)。
     *
     * @param stocksId          股票ID
     * @param stocksShortname   股票简称快照
     * @param effectiveMonth    生效月份
     * @param evidenceStartTime 证据起点
     * @param evidenceEndTime   证据终点
     * @param maturity          成熟度
     * @param metrics           证据指标
     * @return 不完整草稿
     */
    private StockMonthlyStateDraft buildIncompleteDraft(Integer stocksId,
                                                        String stocksShortname,
                                                        LocalDate effectiveMonth,
                                                        LocalDateTime evidenceStartTime,
                                                        LocalDateTime evidenceEndTime,
                                                        StockMaturityEnum maturity,
                                                        StockMonthlyEvidenceMetrics metrics) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("rawPersonality", null);
        snapshot.put("rawRiskLevel", null);
        snapshot.put("usableBarCoverage", metrics.usableBarCoverage());
        snapshot.put("maxMissingBucketGap", metrics.maxMissingBucketGap());
        snapshot.put("evidenceDays", metrics.evidenceDays());
        snapshot.put("incompleteReason", metrics.incompleteReason());
        snapshot.put("hysteresisReason", null);
        return new StockMonthlyStateDraft(
                stocksId, stocksShortname, effectiveMonth,
                evidenceStartTime, evidenceEndTime,
                maturity, null, null, null, null,
                null, null,
                JsonUtils.objToJson(snapshot),
                false, metrics.incompleteReason(), false,
                null);
    }

    // ==================== 内部辅助 ====================

    /**
     * 解析风格编码。
     *
     * @param code 编码
     * @return 枚举;空或非法时返回null
     */
    private StockStrategyFitEnum parseStyle(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return StockStrategyFitEnum.fromCode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析风险编码。
     *
     * @param code 编码
     * @return 枚举;空或非法时返回null
     */
    private StockRiskLevelEnum parseRisk(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return StockRiskLevelEnum.fromCode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析上一状态指标快照为字符串Map。
     *
     * @param metricSnapshot JSON文本
     * @return 快照Map;空或非法时返回空Map
     */
    private Map<String, Object> parseSnapshot(String metricSnapshot) {
        if (metricSnapshot == null || metricSnapshot.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    JsonUtils.getNode(metricSnapshot, "rawPersonality");
            if (root == null) {
                return Map.of();
            }
            return JsonUtils.jsonToObj(metricSnapshot, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 取Map中字符串值。
     *
     * @param map Map
     * @param key 键
     * @return 值(不存在时返回null)
     */
    private String asString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    // ==================== 内部结构 ====================

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

    /**
     * 风格迟滞结果。
     *
     * @param suggested   建议风格
     * @param confirmable 是否可确认
     * @param reason      迟滞原因
     */
    private record PersonalityResolution(StockStrategyFitEnum suggested,
                                         boolean confirmable,
                                         String reason) {
    }

    /**
     * 风险迟滞结果。
     *
     * @param riskLevel   有效风险
     * @param confirmable 是否可确认
     */
    private record RiskResolution(StockRiskLevelEnum riskLevel, boolean confirmable) {
    }
}
