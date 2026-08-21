package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 买入资格评估器 - 信号状态解析、false->true边沿判定、策略绝对趋势守卫与通用资格门禁。
 * <p>
 * 纯规则组件,无DAO、无事务、无写操作。通用资格检查委托给 {@link StockEligibilityService},
 * 边沿与守卫逻辑独立于通用门禁,保证守卫失败仍写原始信号与拒绝观察。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.09
 */
@Slf4j
@Component
public class BuyEligibilityEvaluator {

    private final StockEligibilityService eligibilityService;

    /**
     * 构造资格评估器。
     *
     * @param eligibilityService 通用资格判断服务
     */
    public BuyEligibilityEvaluator(StockEligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    /**
     * 判断命中策略是否存在false->true边沿。
     * <p>
     * 同一股票命中多个策略时,每个策略读取自己的复合状态键;任一策略首次命中即触发本轮信号事件。
     *
     * @param stocksId          股票ID
     * @param matchedStrategies 本轮命中的策略
     * @param signalStateByKey  按股票、策略和规则版本索引的状态
     * @param buyRuleVersion    买入规则版本
     * @return 存在策略边沿时返回true
     */
    public boolean checkEdgeTriggered(Integer stocksId,
                                      List<StockBuyStrategy> matchedStrategies,
                                      Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
                                      String buyRuleVersion) {
        if (stocksId == null || matchedStrategies == null || matchedStrategies.isEmpty()) {
            return false;
        }
        for (StockBuyStrategy strategy : matchedStrategies) {
            if (strategy == null || strategy.getStrategyType() == null) {
                continue;
            }
            StockSignalStateKey key = new StockSignalStateKey(
                    stocksId, strategy.getStrategyType().getCode(), buyRuleVersion);
            TornStockSignalStateDO state = signalStateByKey == null ? null : signalStateByKey.get(key);
            if (state == null || !Boolean.TRUE.equals(state.getConditionActive())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按主策略的复合键(stocksId, strategyType, buyRuleVersion)从映射中查找信号状态。
     *
     * @param stocksId         股票ID
     * @param primaryStrategy  主策略;为null时返回null
     * @param signalStateByKey 按复合键索引的信号状态映射
     * @param buyRuleVersion   买入规则版本
     * @return 对应策略的信号状态;不存在时返回null
     */
    public TornStockSignalStateDO resolveSignalState(
            Integer stocksId,
            StockBuyStrategy primaryStrategy,
            Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
            String buyRuleVersion) {
        if (primaryStrategy == null) {
            return null;
        }
        StockSignalStateKey key = new StockSignalStateKey(
                stocksId,
                primaryStrategy.getStrategyType().getCode(),
                buyRuleVersion);
        return signalStateByKey == null ? null : signalStateByKey.get(key);
    }

    /**
     * 应用主策略的绝对趋势保护守卫(RANGE专属资格检查)。
     * <p>
     * 守卫独立于通用资格门禁: 仅当通用资格为ALLOWED时执行;守卫失败将结果降级为REJECTED并
     * 记录冻结原因码(如 {@code ABSOLUTE_TREND_GUARD_FAILED}),使该失败仍写原始信号与拒绝观察,
     * 不建立正式批次。未设置守卫的策略(默认返回null)保持原资格结果。
     *
     * @param context         买入上下文
     * @param primaryStrategy 主策略;无命中时为null
     * @param eligibility     通用资格判断结果
     * @return 应用守卫后的资格结果
     */
    public EligibilityResult applyStrategyGuard(BuyContext context,
                                                StockBuyStrategy primaryStrategy,
                                                EligibilityResult eligibility) {
        if (primaryStrategy == null || eligibility == null
                || StockEligibilityResultEnum.ALLOWED != eligibility.result()) {
            return eligibility;
        }
        String guardReason = primaryStrategy.absoluteTrendGuardFailureReason(context);
        if (guardReason == null || guardReason.isBlank()) {
            return eligibility;
        }
        log.debug("买入信号未通过策略绝对趋势守卫: stocksId={}, strategy={}, reason={}",
                context.stocksId(), primaryStrategy.getStrategyType(), guardReason);
        return new EligibilityResult(StockEligibilityResultEnum.REJECTED, List.of(guardReason));
    }

    /**
     * 执行通用资格判断并返回结果。
     *
     * @param context              买入评估上下文
     * @param signalState          信号状态记录,可为null
     * @param monthlyState         月度状态记录,可为null
     * @param hasActiveFormalBatch 当前股票是否已有正式活跃批次
     * @param roundTime            本轮时间
     * @return 资格判定结果
     */
    public EligibilityResult checkEligibility(BuyContext context,
                                              TornStockSignalStateDO signalState,
                                              TornStockMonthlyStateDO monthlyState,
                                              boolean hasActiveFormalBatch,
                                              LocalDateTime roundTime) {
        return eligibilityService.checkEligibility(
                context, signalState, monthlyState, hasActiveFormalBatch, roundTime);
    }
}
