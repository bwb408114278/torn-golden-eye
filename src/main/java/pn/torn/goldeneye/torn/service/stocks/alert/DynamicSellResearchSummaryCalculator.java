package pn.torn.goldeneye.torn.service.stocks.alert;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;

import java.util.List;

/**
 * 动态SELL研究摘要计算器 - 纯计算研究mark的分母、完整数、缺失数与展示状态
 * <p>
 * 以 {@code torn_stock_batch_mark} 为唯一数据源表达研究状态而非建议命中:
 * 分母=研究mark数,完整数=decision=NOT_EVALUATED且reason=DYNAMIC_RULE_NOT_FROZEN的mark数,
 * 缺失数=分母-完整数;展示状态仅区分"无研究输入"与"存在研究mark"(公式冻结前固定规则未冻结)。
 * 本类不访问DAO、不触达通知或渲染,是查询服务的纯计算组件。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Component
public class DynamicSellResearchSummaryCalculator {

    /**
     * 汇总研究mark统计。
     *
     * @param researchMarks 研究mark列表
     * @return 研究mark统计
     */
    public DynamicSellResearchSummary summarize(List<TornStockBatchMarkDO> researchMarks) {
        int researchMarkCount = researchMarks == null ? 0 : researchMarks.size();
        int completeResearchMarkCount = countCompleteResearchMarks(researchMarks);
        int missingResearchMarkCount = researchMarkCount - completeResearchMarkCount;
        DisplayState displayState = researchMarkCount <= 0
                ? DisplayState.NO_INPUT : DisplayState.RESEARCH_PRESENT;
        return new DynamicSellResearchSummary(researchMarkCount, completeResearchMarkCount,
                missingResearchMarkCount, displayState);
    }

    /**
     * 统计完整研究mark数(decision=NOT_EVALUATED且reason=DYNAMIC_RULE_NOT_FROZEN)。
     *
     * @param researchMarks 研究mark列表
     * @return 完整研究mark数
     */
    private int countCompleteResearchMarks(List<TornStockBatchMarkDO> researchMarks) {
        if (CollectionUtils.isEmpty(researchMarks)) {
            return 0;
        }
        return (int) researchMarks.stream()
                .filter(mark -> StockDynamicSellResearchConstants.DECISION_NOT_EVALUATED
                        .equals(mark.getDynamicShadowDecision())
                        && StockDynamicSellResearchConstants.REASON_RULE_NOT_FROZEN
                        .equals(mark.getDynamicShadowReason()))
                .count();
    }

    /**
     * 动态SELL研究展示状态。
     */
    public enum DisplayState {
        /**
         * 无研究输入(分母为0)
         */
        NO_INPUT,
        /**
         * 存在研究mark(公式冻结前固定展示规则未冻结与覆盖率)
         */
        RESEARCH_PRESENT
    }

    /**
     * 动态SELL研究统计。
     *
     * @param researchMarkCount         研究mark分母
     * @param completeResearchMarkCount 完整研究mark数
     * @param missingResearchMarkCount  缺失研究mark数(分母-完整数)
     * @param displayState              展示状态
     */
    public record DynamicSellResearchSummary(int researchMarkCount, int completeResearchMarkCount,
                                             int missingResearchMarkCount, DisplayState displayState) {
    }
}
