package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCancelReasonEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票入场结算服务 - 处理待买入批次(ENTRY_PENDING)与待卖出批次(EXIT_PENDING)的成交与取消
 * <p>
 * 对应技术方案12步执行顺序中的步骤2-3:
 * <ul>
 *   <li>步骤2: 处理上一轮待买入批次(成交/取消/过期)</li>
 *   <li>步骤3: 处理上一轮待卖出批次(成交并释放槽位)</li>
 * </ul>
 * 传入的{@link RoundSnapshot}在事务外已批量加载,本服务内不再产生N+1查询。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockEntrySettlementService {

    /**
     * 默认关闭类型(无明确卖出原因时使用)
     */
    private static final StockCloseTypeEnum DEFAULT_CLOSE_TYPE = StockCloseTypeEnum.CLOSED_TARGET;

    private final StockPortfolioService portfolioService;

    // ==================== 步骤2: 处理待买入批次 ====================

    /**
     * 处理ENTRY_PENDING批次: 检查本轮bar是否为紧邻下一连续bar, 成交或取消。
     * <p>
     * 处理规则:
     * <ul>
     *   <li>本轮bar与预期入场bar连续且价格偏离通过 -&gt; 成交(状态OPEN)</li>
     *   <li>本轮bar连续但价格偏离超限 -&gt; 取消(CANCELLED, reason=ENTRY_PRICE_DEVIATION)</li>
     *   <li>超过entryStaleAt仍未成交 -&gt; 取消(CANCELLED, reason=ENTRY_DATA_STALE)</li>
     *   <li>本轮bar不可用或非连续 -&gt; 保持ENTRY_PENDING等待下一轮</li>
     * </ul>
     *
     * @param snapshot   轮次快照
     * @param barByStock 按股票ID索引的bar映射
     * @param roundTime  本轮时间
     * @return 入场结算结果(包含已成交与已取消的批次列表)
     */
    public EntrySettlementResult processEntryPending(RoundSnapshot snapshot,
                                                     Map<Integer, TornStockMarketBar15mDO> barByStock,
                                                     LocalDateTime roundTime) {
        List<TornStockVirtualBatchDO> entryPendingBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.ENTRY_PENDING.getCode().equals(batch.getBatchStatus()))
                .toList();

        List<TornStockVirtualBatchDO> filledBatches = new ArrayList<>();
        List<TornStockVirtualBatchDO> cancelledBatches = new ArrayList<>();

        if (entryPendingBatches.isEmpty()) {
            log.debug("无待买入批次需要处理");
            return new EntrySettlementResult(filledBatches, cancelledBatches);
        }

        Map<Long, TornStockPortfolioSlotDO> slotById = indexSlotsById(snapshot.slots());

        for (TornStockVirtualBatchDO batch : entryPendingBatches) {
            processSingleEntryBatch(batch, barByStock, slotById, roundTime, filledBatches, cancelledBatches);
        }
        return new EntrySettlementResult(filledBatches, cancelledBatches);
    }

    /**
     * 处理单个待买入批次: 判断过期、bar可用性、连续性与价格偏离, 决定成交、取消或等待。
     *
     * @param batch            待买入批次
     * @param barByStock       按股票ID索引的bar映射
     * @param slotById         槽位ID索引映射
     * @param roundTime        本轮时间
     * @param filledBatches    输出: 已成交批次列表
     * @param cancelledBatches 输出: 已取消批次列表
     */
    private void processSingleEntryBatch(TornStockVirtualBatchDO batch,
                                         Map<Integer, TornStockMarketBar15mDO> barByStock,
                                         Map<Long, TornStockPortfolioSlotDO> slotById,
                                         LocalDateTime roundTime,
                                         List<TornStockVirtualBatchDO> filledBatches,
                                         List<TornStockVirtualBatchDO> cancelledBatches) {
        // 检查是否超过过期时间
        if (batch.getEntryStaleAt() != null && roundTime.isAfter(batch.getEntryStaleAt())) {
            cancelEntryBatch(batch, slotById, StockCancelReasonEnum.ENTRY_DATA_STALE, cancelledBatches);
            log.info("待买入批次过期取消: batchNo={}, stocksId={}, staleAt={}, roundTime={}",
                    batch.getBatchNo(), batch.getStocksId(), batch.getEntryStaleAt(), roundTime);
            return;
        }

        TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());

        // 检查本轮bar是否可用
        if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
            log.debug("待买入批次[{}]本轮bar不可用,继续等待: stocksId={}",
                    batch.getBatchNo(), batch.getStocksId());
            return;
        }

        // 检查bar连续性: 本轮bar的barStartTime应等于预期入场bar时间
        if (!isEntryBarConsecutive(batch, currentBar)) {
            log.debug("待买入批次[{}]本轮bar非连续,继续等待: expected={}, actual={}",
                    batch.getBatchNo(), batch.getExpectedEntryBarTime(), currentBar.getBarStartTime());
            return;
        }

        BigDecimal entryReferencePrice = currentBar.getLastPrice();

        // 检查价格偏离
        if (StockPortfolioService.checkEntryPriceDeviation(batch.getSignalReferencePrice(), entryReferencePrice)) {
            cancelEntryBatch(batch, slotById, StockCancelReasonEnum.ENTRY_PRICE_DEVIATION, cancelledBatches);
            log.info("待买入批次价格偏离取消: batchNo={}, signalPrice={}, entryPrice={}",
                    batch.getBatchNo(), batch.getSignalReferencePrice(), entryReferencePrice);
            return;
        }

        // 成交: 状态OPEN
        fillEntryBatch(batch, currentBar, slotById, roundTime, filledBatches);
        log.info("待买入批次成交: batchNo={}, stocksId={}, entryPrice={}",
                batch.getBatchNo(), batch.getStocksId(), entryReferencePrice);
    }

    /**
     * 判断本轮bar与预期入场bar是否连续。
     *
     * @param batch      待买入批次
     * @param currentBar 本轮bar
     * @return 连续返回true;否则false
     */
    private boolean isEntryBarConsecutive(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar) {
        return batch.getExpectedEntryBarTime() != null
                && currentBar.getBarStartTime() != null
                && currentBar.getBarStartTime().equals(batch.getExpectedEntryBarTime());
    }

    /**
     * 成交待买入批次: 状态置为OPEN, 设置入场参考价、入场时间、股数, 占用槽位。
     *
     * @param batch         待成交批次
     * @param currentBar    本轮bar
     * @param slotById      槽位ID索引映射
     * @param roundTime     本轮时间
     * @param filledBatches 输出: 已成交批次列表
     */
    private void fillEntryBatch(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar,
                                Map<Long, TornStockPortfolioSlotDO> slotById,
                                LocalDateTime roundTime,
                                List<TornStockVirtualBatchDO> filledBatches) {
        BigDecimal entryReferencePrice = currentBar.getLastPrice();
        TornStockPortfolioSlotDO slot = batch.getSlotId() != null ? slotById.get(batch.getSlotId()) : null;

        long quantity = 0L;
        BigDecimal investedCash = BigDecimal.ZERO;
        BigDecimal remainingCash = BigDecimal.ZERO;

        if (slot != null) {
            quantity = StockPortfolioService.calculateQuantity(slot.getAvailableCash(), entryReferencePrice);
            if (quantity > 0) {
                investedCash = entryReferencePrice.multiply(BigDecimal.valueOf(quantity));
                remainingCash = slot.getAvailableCash().subtract(investedCash);
                portfolioService.occupySlot(slot, quantity, entryReferencePrice, batch.getId());
            }
        }

        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setEntryReferencePrice(entryReferencePrice);
        batch.setEntryTime(roundTime);
        batch.setQuantity(quantity);
        batch.setInvestedCash(investedCash);
        batch.setRemainingCash(remainingCash);
        batch.setPeakPrice(entryReferencePrice);
        batch.setTroughPrice(entryReferencePrice);
        batch.setCurrentNetReturn(BigDecimal.ZERO);
        batch.setMfe(BigDecimal.ZERO);
        batch.setMae(BigDecimal.ZERO);
        batch.setPeakDrawdown(BigDecimal.ZERO);
        batch.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
        batch.setSellRuleVersion(StockRoundTransactionService.SELL_RULE_VERSION);
        batch.setAllocationRuleVersion(StockRoundTransactionService.ALLOCATION_RULE_VERSION);
        batch.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);

        filledBatches.add(batch);
    }

    /**
     * 取消待买入批次: 状态置为CANCELLED, 释放槽位。
     *
     * @param batch            待取消批次
     * @param slotById         槽位ID索引映射
     * @param reason           取消原因
     * @param cancelledBatches 输出: 已取消批次列表
     */
    private void cancelEntryBatch(TornStockVirtualBatchDO batch,
                                  Map<Long, TornStockPortfolioSlotDO> slotById,
                                  StockCancelReasonEnum reason,
                                  List<TornStockVirtualBatchDO> cancelledBatches) {
        if (batch.getSlotId() != null) {
            TornStockPortfolioSlotDO slot = slotById.get(batch.getSlotId());
            if (slot != null) {
                portfolioService.releaseSlot(slot);
            }
        }
        batch.setBatchStatus(StockBatchStatusEnum.CANCELLED.getCode());
        batch.setCancelReason(reason.getCode());
        cancelledBatches.add(batch);
    }

    // ==================== 步骤3: 处理待卖出批次 ====================

    /**
     * 处理EXIT_PENDING批次: 检查本轮bar是否为紧邻下一连续bar, 成交则关闭批次并释放槽位。
     * <p>
     * 处理规则:
     * <ul>
     *   <li>本轮bar与预期平仓bar连续 -&gt; 成交(状态CLOSED_xxx, 设置exitReferencePrice/exitTime/netReturn)</li>
     *   <li>本轮bar不可用或非连续 -&gt; 保持EXIT_PENDING等待下一轮</li>
     * </ul>
     *
     * @param snapshot   轮次快照
     * @param barByStock 按股票ID索引的bar映射
     * @param roundTime  本轮时间
     * @return 已成交的卖出批次列表
     */
    public List<TornStockVirtualBatchDO> processExitPending(RoundSnapshot snapshot,
                                                            Map<Integer, TornStockMarketBar15mDO> barByStock,
                                                            LocalDateTime roundTime) {
        List<TornStockVirtualBatchDO> exitPendingBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.EXIT_PENDING.getCode().equals(batch.getBatchStatus()))
                .toList();

        List<TornStockVirtualBatchDO> filledBatches = new ArrayList<>();

        if (exitPendingBatches.isEmpty()) {
            log.debug("无待卖出批次需要处理");
            return filledBatches;
        }

        Map<Long, TornStockPortfolioSlotDO> slotById = indexSlotsById(snapshot.slots());

        for (TornStockVirtualBatchDO batch : exitPendingBatches) {
            processSingleExitBatch(batch, barByStock, slotById, roundTime, filledBatches);
        }
        return filledBatches;
    }

    /**
     * 处理单个待卖出批次: 判断bar可用性与连续性, 决定成交或等待。
     *
     * @param batch         待卖出批次
     * @param barByStock    按股票ID索引的bar映射
     * @param slotById      槽位ID索引映射
     * @param roundTime     本轮时间
     * @param filledBatches 输出: 已成交批次列表
     */
    private void processSingleExitBatch(TornStockVirtualBatchDO batch,
                                        Map<Integer, TornStockMarketBar15mDO> barByStock,
                                        Map<Long, TornStockPortfolioSlotDO> slotById,
                                        LocalDateTime roundTime,
                                        List<TornStockVirtualBatchDO> filledBatches) {
        TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());

        // 检查本轮bar是否可用
        if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
            log.debug("待卖出批次[{}]本轮bar不可用,继续等待: stocksId={}",
                    batch.getBatchNo(), batch.getStocksId());
            return;
        }

        // 检查bar连续性
        if (!isExitBarConsecutive(batch, currentBar)) {
            log.debug("待卖出批次[{}]本轮bar非连续,继续等待: expected={}, actual={}",
                    batch.getBatchNo(), batch.getExpectedExitBarTime(), currentBar.getBarStartTime());
            return;
        }

        fillExitBatch(batch, currentBar, slotById, roundTime, filledBatches);
        log.info("待卖出批次成交: batchNo={}, stocksId={}, exitPrice={}, closeType={}",
                batch.getBatchNo(), batch.getStocksId(), currentBar.getLastPrice(), batch.getExitReason());
    }

    /**
     * 判断本轮bar与预期平仓bar是否连续。
     *
     * @param batch      待卖出批次
     * @param currentBar 本轮bar
     * @return 连续返回true;否则false
     */
    private boolean isExitBarConsecutive(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar) {
        return batch.getExpectedExitBarTime() != null
                && currentBar.getBarStartTime() != null
                && currentBar.getBarStartTime().equals(batch.getExpectedExitBarTime());
    }

    /**
     * 成交待卖出批次: 状态置为CLOSED_xxx, 设置卖出参考价、卖出时间、净收益, 结算槽位。
     *
     * @param batch         待成交批次
     * @param currentBar    本轮bar
     * @param slotById      槽位ID索引映射
     * @param roundTime     本轮时间
     * @param filledBatches 输出: 已成交批次列表
     */
    private void fillExitBatch(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar,
                               Map<Long, TornStockPortfolioSlotDO> slotById,
                               LocalDateTime roundTime,
                               List<TornStockVirtualBatchDO> filledBatches) {
        BigDecimal exitReferencePrice = currentBar.getLastPrice();
        long quantity = batch.getQuantity() != null ? batch.getQuantity() : 0L;

        // 结算槽位
        if (batch.getSlotId() != null && quantity > 0) {
            TornStockPortfolioSlotDO slot = slotById.get(batch.getSlotId());
            if (slot != null) {
                portfolioService.settleSlot(slot, quantity, exitReferencePrice);
            }
        }

        // 计算净收益
        BigDecimal netReturn = StockPortfolioService.calculateNetReturn(
                batch.getEntryReferencePrice(), exitReferencePrice);
        BigDecimal sellProceeds = quantity > 0
                ? exitReferencePrice.multiply(BigDecimal.valueOf(quantity)).multiply(StockPortfolioService.SELL_FEE_RATE)
                : BigDecimal.ZERO;

        // 确定关闭状态(根据exitReason映射到CLOSED_xxx)
        StockCloseTypeEnum closeTypeEnum = resolveCloseTypeEnum(batch.getExitReason());
        StockBatchStatusEnum closeStatus = mapCloseTypeToBatchStatus(closeTypeEnum);

        batch.setBatchStatus(closeStatus.getCode());
        batch.setExitReferencePrice(exitReferencePrice);
        batch.setExitTime(roundTime);
        batch.setNetReturn(netReturn);
        batch.setSellProceeds(sellProceeds);
        batch.setSellRuleVersion(StockRoundTransactionService.SELL_RULE_VERSION);
        batch.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);

        // 设置冷却时间(平仓后进入冷却期)
        batch.setCooldownUntil(roundTime.plusDays(StockPortfolioService.MAX_HOLD_DAYS));

        filledBatches.add(batch);
    }

    /**
     * 解析关闭类型枚举, 为空时使用默认值, 解析失败时回退到默认值。
     * <p>
     * 提取自原fillExitBatch中的嵌套三元表达式, 降低认知复杂度。
     *
     * @param exitReason 卖出原因编码(可为null)
     * @return 关闭类型枚举
     */
    private StockCloseTypeEnum resolveCloseTypeEnum(String exitReason) {
        if (exitReason == null) {
            return DEFAULT_CLOSE_TYPE;
        }
        return safeParseCloseType(exitReason);
    }

    /**
     * 安全解析关闭类型枚举, 解析失败时返回CLOSED_TARGET。
     *
     * @param code 关闭类型编码
     * @return 关闭类型枚举
     */
    private StockCloseTypeEnum safeParseCloseType(String code) {
        try {
            return StockCloseTypeEnum.fromCode(code);
        } catch (IllegalArgumentException e) {
            log.warn("关闭类型编码解析失败,使用默认值CLOSED_TARGET: code={}", code);
            return DEFAULT_CLOSE_TYPE;
        }
    }

    /**
     * 将关闭类型映射到批次状态。
     *
     * @param closeType 关闭类型
     * @return 对应的批次状态枚举
     */
    private StockBatchStatusEnum mapCloseTypeToBatchStatus(StockCloseTypeEnum closeType) {
        return switch (closeType) {
            case CLOSED_TARGET -> StockBatchStatusEnum.CLOSED_TARGET;
            case CLOSED_RANGE -> StockBatchStatusEnum.CLOSED_RANGE;
            case CLOSED_RISK -> StockBatchStatusEnum.CLOSED_RISK;
            case CLOSED_TIME -> StockBatchStatusEnum.CLOSED_TIME;
            case CLOSED_DYNAMIC -> StockBatchStatusEnum.CLOSED_DYNAMIC;
            case CLOSED_ROTATION -> StockBatchStatusEnum.CLOSED_ROTATION;
            case ADMIN_CLOSED -> StockBatchStatusEnum.ADMIN_CLOSED;
        };
    }

    // ==================== 辅助方法 ====================

    /**
     * 按槽位ID索引槽位列表。
     *
     * @param slots 槽位列表
     * @return 按槽位ID索引的映射
     */
    private Map<Long, TornStockPortfolioSlotDO> indexSlotsById(List<TornStockPortfolioSlotDO> slots) {
        Map<Long, TornStockPortfolioSlotDO> map = new HashMap<>();
        if (slots == null) {
            return map;
        }
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot.getId() != null) {
                map.put(slot.getId(), slot);
            }
        }
        return map;
    }

    // ==================== 内部值对象 ====================

    /**
     * 入场结算结果 - 封装待买入批次处理后的成交与取消结果
     *
     * @param filledBatches    已成交的买入批次列表
     * @param cancelledBatches 已取消的买入批次列表
     */
    public record EntrySettlementResult(
            List<TornStockVirtualBatchDO> filledBatches,
            List<TornStockVirtualBatchDO> cancelledBatches
    ) {
    }
}
