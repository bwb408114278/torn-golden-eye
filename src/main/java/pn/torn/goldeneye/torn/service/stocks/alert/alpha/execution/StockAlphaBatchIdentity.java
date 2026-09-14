package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

/**
 * α批次业务身份写入器 - α初始入场与α换仓新仓的唯一身份写入点。
 *
 * <p>α批次的组合、主策略与四个规则版本在入场/换仓阶段一次性冻结,公共成交组装器只补成交事实,
 * 不得再覆盖为旧版默认值。身份字符串只在此处引用{@link StockAlphaRuleDefinition}常量,
 * 禁止在各编排服务中重复硬编码。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.09
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaBatchIdentity {

    /**
     * 写入α批次冻结业务身份: 组合、主策略与四个规则版本。
     * <p>
     * 只写身份字段,不覆盖成交事实字段(状态、价格、时间、股数、资金、峰谷、跟随窗口等)。
     *
     * @param batch 批次DO
     */
    public static void applyAlphaIdentity(TornStockVirtualBatchDO batch) {
        batch.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        batch.setPrimaryStrategy(StockAlphaRuleDefinition.PRIMARY_STRATEGY);
        batch.setBuyRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        batch.setSellRuleVersion(StockAlphaRuleDefinition.SELL_RULE_VERSION);
        batch.setAllocationRuleVersion(StockAlphaRuleDefinition.ALLOCATION_RULE_VERSION);
        batch.setMessageRuleVersion(StockAlphaRuleDefinition.MESSAGE_RULE_VERSION);
    }
}
