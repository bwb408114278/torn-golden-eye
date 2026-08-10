package pn.torn.goldeneye.torn.service.stocks.alert;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 每日摘要统计计算器 - 纯计算买卖、风险、拒绝等汇总指标
 * <p>
 * 本类只消费信号事件/批次/槽位列表并返回统计数,不访问DAO、不触达通知或渲染,
 * 是查询服务的纯计算组件。买卖批次按entryTime/exitTime落在摘要日期范围内统计,
 * 净收益为卖出批次netReturn之和;拒绝/高风险等口径与原始信号事件编码一一对应。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Component
public class DailySummaryMetricsCalculator {

    /**
     * 统计占用槽位数(状态非AVAILABLE)。
     *
     * @param slots 全部槽位
     * @return 占用槽位数
     */
    public int countOccupiedSlots(List<TornStockPortfolioSlotDO> slots) {
        if (CollectionUtils.isEmpty(slots)) {
            return 0;
        }
        return (int) slots.stream()
                .filter(slot -> !StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .count();
    }

    /**
     * 统计昨日买入或卖出批次数。
     *
     * @param batches  昨日有动作的批次
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @param isBuy    true统计买入(entryTime),false统计卖出(exitTime)
     * @return 批次数
     */
    public int countBatchesInRange(List<TornStockVirtualBatchDO> batches,
                                   LocalDateTime dayStart, LocalDateTime dayEnd, boolean isBuy) {
        if (CollectionUtils.isEmpty(batches)) {
            return 0;
        }
        return (int) batches.stream()
                .filter(batch -> {
                    LocalDateTime time = isBuy ? batch.getEntryTime() : batch.getExitTime();
                    return time != null && !time.isBefore(dayStart) && time.isBefore(dayEnd);
                })
                .count();
    }

    /**
     * 汇总昨日卖出批次的净收益。
     *
     * @param batches  昨日有动作的批次
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 净收益合计;无卖出批次时返回 {@link BigDecimal#ZERO}
     */
    public BigDecimal sumNetReturn(List<TornStockVirtualBatchDO> batches,
                                   LocalDateTime dayStart, LocalDateTime dayEnd) {
        if (CollectionUtils.isEmpty(batches)) {
            return BigDecimal.ZERO;
        }
        return batches.stream()
                .filter(batch -> {
                    LocalDateTime exitTime = batch.getExitTime();
                    return exitTime != null && !exitTime.isBefore(dayStart) && exitTime.isBefore(dayEnd);
                })
                .map(TornStockVirtualBatchDO::getNetReturn)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 提取OPEN状态批次的股票简称列表。
     *
     * @param activeBatches 活跃批次
     * @return 股票简称列表;无开放批次时返回空列表
     */
    public List<String> extractOpenBatchStocks(List<TornStockVirtualBatchDO> activeBatches) {
        if (CollectionUtils.isEmpty(activeBatches)) {
            return Collections.emptyList();
        }
        return activeBatches.stream()
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()))
                .map(TornStockVirtualBatchDO::getStocksShortname)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 统计DATA_STALE状态批次数。
     *
     * @param activeBatches 活跃批次
     * @return 陈旧批次数
     */
    public int countStaleBatches(List<TornStockVirtualBatchDO> activeBatches) {
        if (CollectionUtils.isEmpty(activeBatches)) {
            return 0;
        }
        return (int) activeBatches.stream()
                .filter(batch -> StockBatchStatusEnum.DATA_STALE.getCode().equals(batch.getBatchStatus())
                        || StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(batch.getBatchStatus()))
                .count();
    }

    /**
     * 统计信号事件总数。
     *
     * @param signalEvents 信号事件列表
     * @return 事件数;入参为空时返回0
     */
    public int countSignalEvents(List<TornStockSignalEventDO> signalEvents) {
        return CollectionUtils.isEmpty(signalEvents) ? 0 : signalEvents.size();
    }

    /**
     * 按组合决策统计信号事件数。
     *
     * @param signalEvents 信号事件列表
     * @return 匹配的事件数
     */
    public int countShadowDecisions(List<TornStockSignalEventDO> signalEvents) {
        if (CollectionUtils.isEmpty(signalEvents)) {
            return 0;
        }
        return (int) signalEvents.stream()
                .filter(event -> StockPortfolioDecisionEnum.SHADOW.getCode().equals(event.getPortfolioDecision()))
                .count();
    }

    /**
     * 按拒绝原因统计信号事件数。
     *
     * @param signalEvents 信号事件列表
     * @return 匹配的事件数
     */
    public int countNoAvailableSlotRejections(List<TornStockSignalEventDO> signalEvents) {
        if (CollectionUtils.isEmpty(signalEvents)) {
            return 0;
        }
        return (int) signalEvents.stream()
                .filter(event -> StockCancelReasonEnum.NO_AVAILABLE_SLOT.getCode().equals(event.getRejectReason()))
                .count();
    }

    /**
     * 统计风格/趋势拒绝的信号事件数。
     * <p>
     * 包含两种情形:
     * <ul>
     *   <li>rejectReason = STYLE_NOT_READY</li>
     *   <li>portfolioDecision = REJECTED 且 rejectReason 非 NO_AVAILABLE_SLOT</li>
     * </ul>
     *
     * @param signalEvents 信号事件列表
     * @return 风格/趋势拒绝事件数
     */
    public int countStyleReject(List<TornStockSignalEventDO> signalEvents) {
        if (CollectionUtils.isEmpty(signalEvents)) {
            return 0;
        }
        return (int) signalEvents.stream()
                .filter(event -> {
                    String reason = event.getRejectReason();
                    if (StockCancelReasonEnum.STYLE_NOT_READY.getCode().equals(reason)) {
                        return true;
                    }
                    return StockPortfolioDecisionEnum.REJECTED.getCode().equals(event.getPortfolioDecision())
                            && !StockCancelReasonEnum.NO_AVAILABLE_SLOT.getCode().equals(reason);
                })
                .count();
    }

    /**
     * 统计高风险观察数(riskLevel=HIGH)。
     *
     * @param shadowBatches 影子批次列表
     * @return 高风险观察数
     */
    public int countHighRisk(List<TornStockVirtualBatchDO> shadowBatches) {
        if (CollectionUtils.isEmpty(shadowBatches)) {
            return 0;
        }
        return (int) shadowBatches.stream()
                .filter(batch -> StockRiskLevelEnum.HIGH.getCode().equals(batch.getRiskLevel()))
                .count();
    }
}
