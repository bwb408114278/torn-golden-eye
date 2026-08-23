package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度状态纯计算器 - 按冻结公式 {@code PERSONALITY_RULE_V1} 与 {@code RISK_RULE_V1_SHADOW}
 * 计算月度证据指标、成熟度、六类原始风格、风险投票、迟滞建议与有效风险。
 * <p>
 * 本类只做纯计算与状态判定,不访问数据库、不写业务表、不依赖系统时钟,便于领域测试与回放复用。
 * 证据窗口指标、日级趋势与投票由 {@link StockMonthlyEvidenceComputer} 承担。
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
     * 快照键: 原始风格
     */
    private static final String SNAPSHOT_KEY_RAW_PERSONALITY = "rawPersonality";
    /**
     * 快照键: 原始风险
     */
    private static final String SNAPSHOT_KEY_RAW_RISK_LEVEL = "rawRiskLevel";

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
        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(
                evidenceStartTime, evidenceEndTime, usableBars);
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
                parseStyle(asString(rawMap, SNAPSHOT_KEY_RAW_PERSONALITY)),
                parseRisk(asString(rawMap, SNAPSHOT_KEY_RAW_RISK_LEVEL)));
    }

    // ==================== 成熟度与分类 ====================

    /**
     * 成熟度按证据自然日分级(60/120/240/365)。
     *
     * @param start 证据起点
     * @param end   证据终点
     * @return 成熟度枚举
     */
    private StockMaturityEnum determineMaturity(LocalDateTime start, LocalDateTime end) {
        double days = StockMonthlyEvidenceComputer.evidenceDays(start, end);
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
        if (isDecliner(metrics)) {
            return StockStrategyFitEnum.DECLINER;
        }
        if (isWeak(metrics)) {
            return StockStrategyFitEnum.WEAK;
        }
        if (isNarrow(metrics)) {
            return StockStrategyFitEnum.NARROW;
        }
        if (isRanging(metrics)) {
            return StockStrategyFitEnum.RANGING;
        }
        if (isStrong(metrics)) {
            return StockStrategyFitEnum.STRONG;
        }
        return StockStrategyFitEnum.STEADY;
    }

    /**
     * DECLINER判定: 年化≤-8%且趋势≤-0.6%且后段≤-1%,或连续3月下跌且季度≤-1.5%。
     *
     * @param metrics 证据指标
     * @return 是否DECLINER
     */
    private boolean isDecliner(StockMonthlyEvidenceMetrics metrics) {
        return (metrics.annualizedDisplay() <= -0.08 && metrics.trend30() <= -0.006
                && metrics.secondHalfReturn() <= -0.01)
                || (metrics.negativeMonthStreak() >= 3 && metrics.lastQuarterReturn() <= -0.015);
    }

    /**
     * WEAK判定: 年化≤-2.5%且趋势≤-0.25%,或负月占比≥60%且后段为负,或后段≤-2.5%且趋势为负。
     *
     * @param metrics 证据指标
     * @return 是否WEAK
     */
    private boolean isWeak(StockMonthlyEvidenceMetrics metrics) {
        boolean lowReturn = metrics.annualizedDisplay() <= -0.025 && metrics.trend30() <= -0.0025;
        boolean negativeMonths = metrics.negativeMonthRatio() != null
                && metrics.negativeMonthRatio() >= 0.60 && metrics.secondHalfReturn() < 0;
        boolean weakSecondHalf = metrics.secondHalfReturn() <= -0.025 && metrics.trend30() < 0;
        return lowReturn || negativeMonths || weakSecondHalf;
    }

    /**
     * NARROW判定: 价格带≤4.5%且年化/趋势均近零。
     *
     * @param metrics 证据指标
     * @return 是否NARROW
     */
    private boolean isNarrow(StockMonthlyEvidenceMetrics metrics) {
        return metrics.fullBand() <= 0.045 && Math.abs(metrics.annualizedDisplay()) <= 0.05
                && Math.abs(metrics.trend30()) <= 0.004;
    }

    /**
     * RANGING判定: 价格带≤10%且年化/趋势小幅波动。
     *
     * @param metrics 证据指标
     * @return 是否RANGING
     */
    private boolean isRanging(StockMonthlyEvidenceMetrics metrics) {
        return metrics.fullBand() <= 0.10 && Math.abs(metrics.annualizedDisplay()) <= 0.07
                && Math.abs(metrics.trend30()) <= 0.006;
    }

    /**
     * STRONG判定: 年化≥8%且趋势≥0.6%且后段非负。
     *
     * @param metrics 证据指标
     * @return 是否STRONG
     */
    private boolean isStrong(StockMonthlyEvidenceMetrics metrics) {
        return metrics.annualizedDisplay() >= 0.08 && metrics.trend30() >= 0.006
                && metrics.secondHalfReturn() >= 0;
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
     * @param metrics              证据指标
     * @param rawPersonality       原始风格
     * @param rawRiskLevel         原始风险
     * @param suggestedPersonality 建议风格
     * @param riskLevel            有效风险
     * @param hysteresisReason     迟滞原因
     * @return 指标快照JSON文本
     */
    private String buildMetricSnapshot(StockMonthlyEvidenceMetrics metrics,
                                       StockStrategyFitEnum rawPersonality,
                                       StockRiskLevelEnum rawRiskLevel,
                                       StockStrategyFitEnum suggestedPersonality,
                                       StockRiskLevelEnum riskLevel,
                                       String hysteresisReason) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(SNAPSHOT_KEY_RAW_PERSONALITY, rawPersonality == null ? null : rawPersonality.getCode());
        snapshot.put(SNAPSHOT_KEY_RAW_RISK_LEVEL, rawRiskLevel == null ? null : rawRiskLevel.getCode());
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
        snapshot.put(SNAPSHOT_KEY_RAW_PERSONALITY, null);
        snapshot.put(SNAPSHOT_KEY_RAW_RISK_LEVEL, null);
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
                    JsonUtils.getNode(metricSnapshot, SNAPSHOT_KEY_RAW_PERSONALITY);
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
