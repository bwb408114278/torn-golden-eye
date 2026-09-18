package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * 每日摘要统计计算器 - 纯计算槽位、昨日买卖与已实现收益汇总指标
 * <p>
 * 本类只消费槽位与批次列表并返回统计值,不访问DAO、不触达通知或渲染,是查询服务的纯计算组件。
 * 买卖批次按entryTime/exitTime落在摘要日期范围内统计;已实现净收益以
 * {@code 卖出收入 - 投入成本} 的金额口径汇总,收益率由金额与投入成本在渲染层派生,
 * 不得再用批次上的 {@code netReturn} 比率冒充"净收益"。
 *
 * @author Bai
 * @version 1.6.5
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
     * 汇总昨日卖出批次的已实现净收益金额。
     *
     * @param batches  昨日有动作的批次
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 已实现净收益金额合计;无卖出批次时返回 {@link BigDecimal#ZERO}
     */
    public BigDecimal sumRealizedProfit(List<TornStockVirtualBatchDO> batches,
                                        LocalDateTime dayStart, LocalDateTime dayEnd) {
        return yesterdayExitBatches(batches, dayStart, dayEnd)
                .map(batch -> proceeds(batch).subtract(investedCash(batch)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 汇总昨日卖出批次的投入成本,供渲染层派生收益率。
     *
     * @param batches  昨日有动作的批次
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 投入成本合计;无卖出批次时返回 {@link BigDecimal#ZERO}
     */
    public BigDecimal sumInvestedCash(List<TornStockVirtualBatchDO> batches,
                                      LocalDateTime dayStart, LocalDateTime dayEnd) {
        return yesterdayExitBatches(batches, dayStart, dayEnd)
                .map(this::investedCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
     * 筛选出场时刻落在摘要日内的批次。
     *
     * @param batches  昨日有动作的批次
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 摘要日内卖出的批次流
     */
    private Stream<TornStockVirtualBatchDO> yesterdayExitBatches(List<TornStockVirtualBatchDO> batches,
                                                                 LocalDateTime dayStart, LocalDateTime dayEnd) {
        if (CollectionUtils.isEmpty(batches)) {
            return Stream.empty();
        }
        return batches.stream().filter(batch -> {
            LocalDateTime exitTime = batch.getExitTime();
            return exitTime != null && !exitTime.isBefore(dayStart) && exitTime.isBefore(dayEnd);
        });
    }

    /**
     * 取批次卖出收入,缺失按0处理。
     *
     * @param batch 批次
     * @return 卖出收入
     */
    private BigDecimal proceeds(TornStockVirtualBatchDO batch) {
        return batch.getSellProceeds() == null ? BigDecimal.ZERO : batch.getSellProceeds();
    }

    /**
     * 取批次投入成本,缺失按0处理。
     *
     * @param batch 批次
     * @return 投入成本
     */
    private BigDecimal investedCash(TornStockVirtualBatchDO batch) {
        return batch.getInvestedCash() == null ? BigDecimal.ZERO : batch.getInvestedCash();
    }
}
