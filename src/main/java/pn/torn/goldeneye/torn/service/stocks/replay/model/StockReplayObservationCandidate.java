package pn.torn.goldeneye.torn.service.stocks.replay.model;

import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalResult.SignalEvaluation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalResult;

/**
 * 待结算理论观察候选(收尾阶段使用全窗口数据计算前向路径)。
 *
 * <p>由回放引擎在轮次内收集,收尾阶段按观察轨道写出: 拒绝观察计算理论路径或无理论入场,
 * 原始BUY对照与高风险观察仅记录观察结果。全部字段来自信号评估,确定性输出。</p>
 *
 * @param track                观察轨道
 * @param roundTime            信号轮次时间
 * @param stocksId             股票ID
 * @param stocksShortname      股票简称
 * @param strategyType         主策略编码
 * @param qualityScore         质量分
 * @param monthlyStyle         冻结风格
 * @param riskLevel            冻结风险
 * @param eligibilityResult    资格结果编码
 * @param eligibilityReasons   资格原因
 * @param candidateRank        候选排名
 * @param portfolioDecision    组合决策
 * @param rejectReason         拒绝/观察原因编码
 * @param signalReferencePrice 信号参考价
 * @param expectedEntryBarTime 期望入场bar时间
 * @param entryStaleAt         入场过期时间
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplayObservationCandidate(
        String track,
        LocalDateTime roundTime,
        Integer stocksId,
        String stocksShortname,
        String strategyType,
        BigDecimal qualityScore,
        String monthlyStyle,
        String riskLevel,
        String eligibilityResult,
        String eligibilityReasons,
        Integer candidateRank,
        String portfolioDecision,
        String rejectReason,
        BigDecimal signalReferencePrice,
        LocalDateTime expectedEntryBarTime,
        LocalDateTime entryStaleAt
) {

    /**
     * 由资格拒绝信号构建拒绝观察候选。
     *
     * @param evaluation    信号评估
     * @param rejectReason  拒绝原因编码
     * @param candidateRank 候选排名(满仓等组合级拒绝时非空)
     * @param t             信号轮次时间
     * @return 拒绝观察候选
     */
    public static StockReplayObservationCandidate ofRejection(
            SignalEvaluation evaluation, String rejectReason,
            Integer candidateRank, LocalDateTime t) {
        return new StockReplayObservationCandidate(
                StockReplayTrackEnum.REJECTION_OBSERVATION.getCode(),
                t, evaluation.stocksId(), evaluation.stocksShortname(),
                evaluation.primaryStrategy().getStrategyType().getCode(),
                evaluation.qualityScore(),
                monthlyStyle(evaluation), riskLevel(evaluation),
                resultCode(evaluation), reasons(evaluation), candidateRank,
                "REJECTED",
                rejectReason,
                referencePrice(evaluation),
                t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES),
                t.plusMinutes(35));
    }

    /**
     * 由边沿命中信号构建原始BUY对照候选。
     *
     * @param evaluation 信号评估
     * @param t          信号轮次时间
     * @return 原始BUY对照候选
     */
    public static StockReplayObservationCandidate ofRawBuy(
            SignalEvaluation evaluation, LocalDateTime t) {
        return new StockReplayObservationCandidate(
                StockReplayTrackEnum.RAW_BUY_CONTROL.getCode(),
                t, evaluation.stocksId(), evaluation.stocksShortname(),
                evaluation.primaryStrategy().getStrategyType().getCode(),
                evaluation.qualityScore(),
                monthlyStyle(evaluation), riskLevel(evaluation),
                resultCode(evaluation), reasons(evaluation), null,
                "OBSERVED", "RAW_BUY_SIGNAL",
                referencePrice(evaluation),
                t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES),
                t.plusMinutes(35));
    }

    /**
     * 由高风险信号构建高风险观察候选。
     *
     * @param evaluation 信号评估
     * @param t          信号轮次时间
     * @return 高风险观察候选
     */
    public static StockReplayObservationCandidate ofHighRisk(SignalEvaluation evaluation, LocalDateTime t) {
        return new StockReplayObservationCandidate(
                StockReplayTrackEnum.HIGH_RISK_OBSERVATION.getCode(),
                t, evaluation.stocksId(), evaluation.stocksShortname(),
                evaluation.primaryStrategy().getStrategyType().getCode(),
                evaluation.qualityScore(),
                monthlyStyle(evaluation), riskLevel(evaluation),
                resultCode(evaluation), reasons(evaluation), null,
                "OBSERVED", "HIGH_RISK_OBSERVATION",
                referencePrice(evaluation),
                t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES),
                t.plusMinutes(35));
    }

    private static String monthlyStyle(SignalEvaluation evaluation) {
        return evaluation.monthlyState() == null ? null : evaluation.monthlyState().getStrategyFitPrior();
    }

    private static String riskLevel(SignalEvaluation evaluation) {
        return evaluation.context() == null || evaluation.context().riskLevel() == null
                ? null : evaluation.context().riskLevel().getCode();
    }

    private static String resultCode(SignalEvaluation evaluation) {
        return evaluation.eligibilityResult() == null ? null
                : evaluation.eligibilityResult().result().getCode();
    }

    private static String reasons(SignalEvaluation evaluation) {
        if (evaluation.eligibilityResult() == null
                || evaluation.eligibilityResult().reasons().isEmpty()) {
            return null;
        }
        return String.join("|", evaluation.eligibilityResult().reasons());
    }

    private static BigDecimal referencePrice(SignalEvaluation evaluation) {
        return evaluation.context() == null ? null : evaluation.context().referencePrice();
    }
}
