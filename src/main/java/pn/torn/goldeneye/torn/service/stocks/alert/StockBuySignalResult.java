package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 买入信号评估结果与单股信号评估值对象集合。
 * <p>
 * 从 {@link StockBuySignalEvaluator} 拆分为顶层记录,使纯规则评估器与
 * 候选接纳、影子记录写入解耦,并允许 {@link StockShadowTrackRecorder}、
 * {@link StockSignalStateUpdater} 与候选接纳服务共享同一只读事实。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
public final class StockBuySignalResult {

    private StockBuySignalResult() {
    }

    /**
     * 买入信号评估结果。
     *
     * @param formalCandidates 通过资格的正式候选列表
     * @param allEvaluations   全部信号评估结果(含拒绝/观察)
     */
    public record BuySignalResult(
            List<CandidateInfo> formalCandidates,
            List<SignalEvaluation> allEvaluations
    ) {
    }

    /**
     * 信号评估结果 - 封装单支股票本轮买入信号评估的全部中间结果。
     * <p>
     * 使用 record + Builder 模式封装跨步骤只读事实,由 {@link StockBuySignalEvaluator#evaluateSignals}
     * 生成,供后续影子记录、通知审计与信号状态更新消费。
     *
     * @param stocksId            股票ID
     * @param stocksShortname     股票简称
     * @param evaluatedStrategies 本轮实际评估的全部买入策略
     * @param primaryStrategy     主策略(质量分最高的命中策略)
     * @param matchedStrategies   全部命中策略列表
     * @param qualityScore        主策略质量分
     * @param edgeTriggered       是否为 false->true 边沿触发
     * @param context             买入上下文
     * @param monthlyState        月度状态记录
     * @param eligibilityResult   资格判定结果;非边沿触发时为 null
     * @param acceptedFormal      是否被正式接纳
     */
    public record SignalEvaluation(
            Integer stocksId,
            String stocksShortname,
            List<StockBuyStrategy> evaluatedStrategies,
            StockBuyStrategy primaryStrategy,
            List<StockBuyStrategy> matchedStrategies,
            BigDecimal qualityScore,
            boolean edgeTriggered,
            BuyContext context,
            TornStockMonthlyStateDO monthlyState,
            EligibilityResult eligibilityResult,
            boolean acceptedFormal
    ) implements StockShadowTrackRecorder.SignalEvaluationView,
            StockSignalStateUpdater.SignalStateEvaluationView {

        /**
         * 创建可变构建器。
         *
         * @param stocksId        股票ID
         * @param stocksShortname 股票简称
         * @return 构建器实例
         */
        public static Builder builder(Integer stocksId, String stocksShortname) {
            return new Builder(stocksId, stocksShortname);
        }

        /**
         * SignalEvaluation 可变构建器,逐步填充字段后构建不可变 record。
         */
        public static class Builder {
            private final Integer stocksId;
            private final String stocksShortname;
            private List<StockBuyStrategy> evaluatedStrategies = List.of();
            private StockBuyStrategy primaryStrategy;
            private List<StockBuyStrategy> matchedStrategies;
            private BigDecimal qualityScore;
            private boolean edgeTriggered;
            private BuyContext context;
            private TornStockMonthlyStateDO monthlyState;
            private EligibilityResult eligibilityResult;
            private boolean acceptedFormal;

            private Builder(Integer stocksId, String stocksShortname) {
                this.stocksId = stocksId;
                this.stocksShortname = stocksShortname;
            }

            /**
             * 设置本轮实际评估的全部买入策略。
             *
             * @param evaluatedStrategies 已评估策略
             * @return 当前构建器
             */
            public Builder evaluatedStrategies(List<StockBuyStrategy> evaluatedStrategies) {
                this.evaluatedStrategies = evaluatedStrategies == null ? List.of() : evaluatedStrategies;
                return this;
            }

            /**
             * 设置主策略。
             *
             * @param primaryStrategy 主策略
             * @return 当前构建器
             */
            public Builder primaryStrategy(StockBuyStrategy primaryStrategy) {
                this.primaryStrategy = primaryStrategy;
                return this;
            }

            /**
             * 设置全部命中策略列表。
             *
             * @param matchedStrategies 命中策略列表
             * @return 当前构建器
             */
            public Builder matchedStrategies(List<StockBuyStrategy> matchedStrategies) {
                this.matchedStrategies = matchedStrategies;
                return this;
            }

            /**
             * 设置主策略质量分。
             *
             * @param qualityScore 质量分
             * @return 当前构建器
             */
            public Builder qualityScore(BigDecimal qualityScore) {
                this.qualityScore = qualityScore;
                return this;
            }

            /**
             * 设置是否为边沿触发。
             *
             * @param edgeTriggered 是否边沿触发
             * @return 当前构建器
             */
            public Builder edgeTriggered(boolean edgeTriggered) {
                this.edgeTriggered = edgeTriggered;
                return this;
            }

            /**
             * 设置买入上下文。
             *
             * @param context 买入上下文
             * @return 当前构建器
             */
            public Builder context(BuyContext context) {
                this.context = context;
                return this;
            }

            /**
             * 设置月度状态记录。
             *
             * @param monthlyState 月度状态
             * @return 当前构建器
             */
            public Builder monthlyState(TornStockMonthlyStateDO monthlyState) {
                this.monthlyState = monthlyState;
                return this;
            }

            /**
             * 设置资格判定结果。
             *
             * @param eligibilityResult 资格结果
             * @return 当前构建器
             */
            public Builder eligibilityResult(EligibilityResult eligibilityResult) {
                this.eligibilityResult = eligibilityResult;
                return this;
            }

            /**
             * 设置是否被正式接纳。
             *
             * @param acceptedFormal 是否正式接纳
             * @return 当前构建器
             */
            public Builder acceptedFormal(boolean acceptedFormal) {
                this.acceptedFormal = acceptedFormal;
                return this;
            }

            /**
             * 构建不可变的 SignalEvaluation。
             * <p>
             * 策略集合在构建时做防御性拷贝,避免调用方持有的可变列表在构建后被外部修改。
             *
             * @return SignalEvaluation 实例
             */
            public SignalEvaluation build() {
                List<StockBuyStrategy> safeEvaluated = evaluatedStrategies == null
                        ? List.of() : List.copyOf(evaluatedStrategies);
                List<StockBuyStrategy> safeMatched = matchedStrategies == null
                        ? List.of() : List.copyOf(matchedStrategies);
                return new SignalEvaluation(
                        stocksId, stocksShortname, safeEvaluated, primaryStrategy, safeMatched,
                        qualityScore, edgeTriggered, context,
                        monthlyState, eligibilityResult, acceptedFormal
                );
            }
        }
    }
}
