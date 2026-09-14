package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockAlphaRebalanceLegEnum;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;

/**
 * α换仓双腿统一关联事实 - 一次目标变化换仓产生的两条通知审计共享的关联来源。
 * <p>
 * 目标变化决策ID、原仓批次ID与新仓批次ID必须在同一次换仓的两条通知审计中都能直接读回,
 * 使业务Review可以从任一换仓通知回答"这是哪一次Alpha目标变化、原仓批次是什么、新仓批次是什么"。
 * 关联标识由目标变化决策ID稳定生成,固定格式为{@code ALPHA_REBALANCE:{rebalanceDecisionId}}。
 * <p>
 * 本类型只承载关联事实,不承载腿标识与腿顺序:腿标识由通知所属的买入/卖出方向决定,
 * 由通知审计写入器按{@link StockAlphaRebalanceLegEnum}补充。
 *
 * @param rebalanceDecisionId 目标变化决策表{@code torn_stock_alpha_decision.id}
 * @param originalBatchId     被换出的原α BUY批次ID
 * @param replacementBatchId  换仓后新的Top1批次ID
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.11
 */
public record NoticeRebalanceAssociation(
        Long rebalanceDecisionId,
        Long originalBatchId,
        Long replacementBatchId) {
    /**
     * 换仓关联标识前缀,与{@link StockAlphaRuleDefinition#EXIT_REASON_REBALANCE}共用同一换仓类型编码。
     */
    private static final String ASSOCIATION_ID_PREFIX = StockAlphaRuleDefinition.EXIT_REASON_REBALANCE + ":";

    /**
     * 校验关联事实完整,拒绝缺少任一换仓来源ID的不完整关联。
     */
    public NoticeRebalanceAssociation {
        if (rebalanceDecisionId == null || rebalanceDecisionId <= 0
                || originalBatchId == null || originalBatchId <= 0
                || replacementBatchId == null || replacementBatchId <= 0) {
            throw new IllegalArgumentException("α换仓关联事实不完整: rebalanceDecisionId=" + rebalanceDecisionId
                    + ", originalBatchId=" + originalBatchId + ", replacementBatchId=" + replacementBatchId);
        }
    }

    /**
     * 返回由目标变化决策ID稳定生成的换仓关联标识。
     *
     * @return 固定格式{@code ALPHA_REBALANCE:{rebalanceDecisionId}}的关联标识
     */
    public String associationId() {
        return ASSOCIATION_ID_PREFIX + rebalanceDecisionId;
    }
}
