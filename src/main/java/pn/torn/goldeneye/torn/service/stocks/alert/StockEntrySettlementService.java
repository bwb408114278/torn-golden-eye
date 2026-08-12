package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCancelReasonEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchEntryFields;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * @version 1.2.14
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockEntrySettlementService {

    /**
     * 正常关闭冷却时长(小时): 目标、区间、时间、动态和换仓关闭使用24小时。
     * 数据/管理关闭(DATA_STALE_EXIT灾难处置)单独使用48小时,见 {@link #DISASTER_COOLDOWN_HOURS}。
     */
    private static final int NORMAL_COOLDOWN_HOURS = 24;
    /**
     * 风险关闭冷却时长(小时): 风险退出关闭需更长冷却期。
     */
    private static final int RISK_COOLDOWN_HOURS = 48;
    /**
     * 数据异常关闭冷却时长(小时): DATA_STALE_EXIT灾难处置统一使用48小时保守冷却。
     */
    private static final int DISASTER_COOLDOWN_HOURS = 48;
    /**
     * 灾难恢复管理关闭原因编码: 写入批次adminCloseReason字段,表达最终管理关闭原因。
     */
    public static final String ADMIN_CLOSE_REASON_DISASTER_RECOVERY = "DATA_STALE_EXIT_RECOVERY_CLOSE";
    /**
     * 真实关闭状态编码
     */
    private static final String[] FILLED_CLOSE_STATUSES = {
            "CLOSED_TARGET", "CLOSED_RANGE", "CLOSED_RISK", "CLOSED_TIME",
            "CLOSED_DYNAMIC", "CLOSED_ROTATION", "ADMIN_CLOSED"
    };
    /**
     * 冻结的正式策略关闭类型集合。
     * <p>
     * 正常正式SELL与灾难关闭的退出事实来源必须属于该集合,用于结算前来源状态校验
     * (P1-4): 正常SELL要求 exitReason ∈ 该集合;灾难关闭要求 originalExitReason ∈ 该集合。
     * ADMIN_CLOSED是批次终态而非原策略退出事实,不包含在集合内。
     */
    private static final Set<String> FROZEN_STRATEGY_CLOSE_TYPES = Set.of(
            "CLOSED_TARGET", "CLOSED_RANGE", "CLOSED_RISK", "CLOSED_TIME",
            "CLOSED_DYNAMIC", "CLOSED_ROTATION"
    );
    /**
     * 运维告警WARN限频间隔小时数: 同一批次每小时最多输出一次结构化WARN,避免15分钟轮次日志噪声。
     */
    private static final long STALE_EXIT_WARN_INTERVAL_HOURS = 1;
    /**
     * DATA_STALE_EXIT批次最近一次结构化WARN输出时间索引(batchId -> 最近输出时间),用于每小时限频。
     * JVM内存态,重启后重新计算,不影响交易正确性,仅用于日志降噪。
     */
    private final Map<Long, LocalDateTime> lastStaleExitWarnAt = new java.util.concurrent.ConcurrentHashMap<>();

    private final StockPortfolioService portfolioService;

    // ==================== 步骤2: 处理待买入批次 ====================

    /**
     * 处理ENTRY_PENDING批次: 检查本轮bar是否为紧邻下一连续bar, 成交或取消。
     * <p>
     * 处理规则:
     * <ul>
     *   <li>本轮bar与预期入场bar连续且价格偏离通过 -&gt; 成交(状态OPEN)</li>
     *   <li>本轮bar连续但价格偏离超限 -&gt; 取消(CANCELLED, reason=ENTRY_PRICE_DEVIATION)</li>
     *   <li>实际处理时刻晚于entryStaleAt(严格大于) -&gt; 取消(CANCELLED, reason=ENTRY_DATA_STALE);
     *       等于staleAt边界仍继续检查目标bar、连续性与价格偏离</li>
     *   <li>本轮bar不可用或非连续 -&gt; 保持ENTRY_PENDING等待下一轮</li>
     * </ul>
     * <p>
     * 时间语义拆分:
     * <ul>
     *   <li>{@code roundTime} 为历史决策/成交bar的业务锚点,仅用于成交时刻等历史事实;</li>
     *   <li>{@code actualProcessingTime} 为本次真实执行/恢复时刻,只用于过期(entryStaleAt)判定,
     *       保证启动补偿/晚恢复场景不会把历史轮次当作未过期而补发BUY。</li>
     * </ul>
     *
     * @param snapshot             轮次快照
     * @param barByStock           按股票ID索引的bar映射
     * @param roundTime            历史决策/成交bar的业务锚点
     * @param actualProcessingTime 本次实际处理时刻(仅用于entryStaleAt过期判定)
     * @return 入场结算结果(包含已成交与已取消的批次列表)
     */
    public EntrySettlementResult processEntryPending(RoundSnapshot snapshot,
                                                     Map<Integer, TornStockMarketBar15mDO> barByStock,
                                                     LocalDateTime roundTime,
                                                     LocalDateTime actualProcessingTime) {
        List<TornStockVirtualBatchDO> entryPendingBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.ENTRY_PENDING.getCode().equals(batch.getBatchStatus()))
                .toList();

        List<TornStockVirtualBatchDO> filledBatches = new ArrayList<>();
        List<TornStockVirtualBatchDO> cancelledBatches = new ArrayList<>();

        if (entryPendingBatches.isEmpty()) {
            log.debug("无待买入批次需要处理");
            return new EntrySettlementResult(filledBatches, cancelledBatches);
        }

        Map<Long, TornStockPortfolioSlotDO> slotById = StockPortfolioService.indexSlotsById(snapshot.slots());

        for (TornStockVirtualBatchDO batch : entryPendingBatches) {
            processSingleEntryBatch(batch, barByStock, slotById, roundTime, actualProcessingTime,
                    filledBatches, cancelledBatches);
        }
        return new EntrySettlementResult(filledBatches, cancelledBatches);
    }

    /**
     * 处理单个待买入批次: 判断过期、bar可用性、连续性与价格偏离, 决定成交、取消或等待。
     *
     * @param batch                待买入批次
     * @param barByStock           按股票ID索引的bar映射
     * @param slotById             槽位ID索引映射
     * @param roundTime            历史决策/成交bar的业务锚点
     * @param actualProcessingTime 本次实际处理时刻(仅用于entryStaleAt过期判定)
     * @param filledBatches        输出: 已成交批次列表
     * @param cancelledBatches     输出: 已取消批次列表
     */
    private void processSingleEntryBatch(TornStockVirtualBatchDO batch,
                                         Map<Integer, TornStockMarketBar15mDO> barByStock,
                                         Map<Long, TornStockPortfolioSlotDO> slotById,
                                         LocalDateTime roundTime,
                                         LocalDateTime actualProcessingTime,
                                         List<TornStockVirtualBatchDO> filledBatches,
                                         List<TornStockVirtualBatchDO> cancelledBatches) {
        // 检查是否超过过期时间: 以实际处理时刻判定,仅actualProcessingTime晚于staleAt才取消,
        // 等于staleAt边界仍继续检查目标bar、连续性与价格偏离(冻结业务规则: 严格大于才取消)
        if (batch.getEntryStaleAt() != null && actualProcessingTime.isAfter(batch.getEntryStaleAt())) {
            cancelEntryBatch(batch, slotById, StockCancelReasonEnum.ENTRY_DATA_STALE, cancelledBatches);
            log.info("待买入批次过期取消: batchNo={}, stocksId={}, staleAt={}, actualProcessingTime={}, roundTime={}",
                    batch.getBatchNo(), batch.getStocksId(), batch.getEntryStaleAt(),
                    actualProcessingTime, roundTime);
            return;
        }

        TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());

        // 检查本轮bar是否可用
        if (!Stock15mBarBuildService.isUsable(currentBar)) {
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
        fillEntryBatch(batch, currentBar, slotById, roundTime, filledBatches, cancelledBatches);
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
     * <p>
     * 使用槽位的 {@code reservedCash}(预留资金)而非 {@code availableCash}(已被reserveSlot扣减为0)
     * 计算股数与实际成本。quantity &lt;= 0 时fail-closed取消并释放预留,不置为OPEN。
     * 同时冻结跟随窗口字段(followUntil/followMaxPrice)。
     *
     * @param batch            待成交批次
     * @param currentBar       本轮bar
     * @param slotById         槽位ID索引映射
     * @param roundTime        本轮时间
     * @param filledBatches    输出: 已成交批次列表
     * @param cancelledBatches 输出: 已取消批次列表(供quantity<=0时使用)
     */
    private void fillEntryBatch(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar,
                                Map<Long, TornStockPortfolioSlotDO> slotById,
                                LocalDateTime roundTime,
                                List<TornStockVirtualBatchDO> filledBatches,
                                List<TornStockVirtualBatchDO> cancelledBatches) {
        if (StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(batch.getLedgerType())) {
            fillShadowEntryBatch(batch, currentBar, roundTime, filledBatches);
            return;
        }
        if (!portfolioService.isSlotBackedLedger(batch.getLedgerType())) {
            throw new IllegalStateException("待买入批次账本类型非法,禁止非正式/候选影子即正式: batchNo="
                    + batch.getBatchNo() + ", ledgerType=" + batch.getLedgerType());
        }

        BigDecimal entryReferencePrice = currentBar.getLastPrice();
        TornStockPortfolioSlotDO slot = batch.getSlotId() != null ? slotById.get(batch.getSlotId()) : null;

        if (slot == null) {
            log.error("待买入批次[{}]槽位不存在,fail-closed取消: slotId={}",
                    batch.getBatchNo(), batch.getSlotId());
            cancelEntryBatch(batch, slotById, StockCancelReasonEnum.ENTRY_PRICE_DEVIATION, cancelledBatches);
            return;
        }

        BigDecimal reservedCash = slot.getReservedCash() == null ? BigDecimal.ZERO : slot.getReservedCash();
        long quantity = StockPortfolioService.calculateQuantity(reservedCash, entryReferencePrice);
        if (quantity <= 0) {
            log.warn("待买入批次[{}]预留资金不足买入1股,fail-closed取消: reservedCash={}, price={}",
                    batch.getBatchNo(), reservedCash, entryReferencePrice);
            cancelEntryBatch(batch, slotById, StockCancelReasonEnum.ENTRY_PRICE_DEVIATION, cancelledBatches);
            return;
        }

        BigDecimal investedCash = entryReferencePrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal remainingCash = reservedCash.subtract(investedCash);

        portfolioService.occupySlot(slot, quantity, entryReferencePrice, batch.getId());

        TornStockVirtualBatchEntryFields fields = new TornStockVirtualBatchEntryFields();
        fields.setEntryReferencePrice(entryReferencePrice);
        fields.setEntryTime(roundTime);
        fields.setQuantity(quantity);
        fields.setInvestedCash(investedCash);
        fields.setRemainingCash(remainingCash);
        StockVirtualBatchAssembler.applyFilledEntryFields(batch, fields);

        filledBatches.add(batch);
    }

    /**
     * 成交无限资金影子批次,不读取、不修改正式槽位。
     *
     * @param batch         待成交影子批次
     * @param currentBar    当前连续bar
     * @param roundTime     本轮时间
     * @param filledBatches 输出: 已成交批次列表
     */
    private void fillShadowEntryBatch(TornStockVirtualBatchDO batch,
                                      TornStockMarketBar15mDO currentBar,
                                      LocalDateTime roundTime,
                                      List<TornStockVirtualBatchDO> filledBatches) {
        BigDecimal entryReferencePrice = currentBar.getLastPrice();
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0) {
            batch.setBatchStatus(StockBatchStatusEnum.CANCELLED.getCode());
            batch.setCancelReason(StockCancelReasonEnum.ENTRY_PRICE_DEVIATION.getCode());
            return;
        }
        TornStockVirtualBatchEntryFields fields = new TornStockVirtualBatchEntryFields();
        fields.setEntryReferencePrice(entryReferencePrice);
        fields.setEntryTime(roundTime);
        fields.setQuantity(1L);
        fields.setInvestedCash(entryReferencePrice);
        fields.setRemainingCash(BigDecimal.ZERO);
        StockVirtualBatchAssembler.applyFilledEntryFields(batch, fields);
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
     * 处理待卖出批次: 检查本轮bar是否为紧邻下一连续bar, 成交则关闭批次并释放槽位。
     * <p>
     * 结果仅返回真实完成参考卖出的CLOSED_*批次。DATA_STALE_EXIT只更新批次状态,
     * 不进入通知发送列表,避免生成没有成交价格的SELL通知。
     * <p>
     * 同时处理上一轮遗留的 {@code DATA_STALE_EXIT} 批次: 等待该股票恢复后的首个可用bar,
     * 按冻结的独立灾难处置规则以管理关闭参考价结算并释放槽位。
     *
     * @param snapshot   轮次快照
     * @param barByStock 按股票ID索引的bar映射
     * @param roundTime  本轮时间
     * @return 已完成真实卖出或灾难关闭的批次列表
     */
    public List<TornStockVirtualBatchDO> processExitPending(RoundSnapshot snapshot,
                                                            Map<Integer, TornStockMarketBar15mDO> barByStock,
                                                            LocalDateTime roundTime) {
        List<TornStockVirtualBatchDO> exitPendingBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.EXIT_PENDING.getCode().equals(batch.getBatchStatus()))
                .toList();
        List<TornStockVirtualBatchDO> staleExitBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(batch.getBatchStatus()))
                .toList();

        List<TornStockVirtualBatchDO> filledBatches = new ArrayList<>();
        if (exitPendingBatches.isEmpty() && staleExitBatches.isEmpty()) {
            log.debug("无待卖出或数据陈旧卖出批次需要处理");
            return filledBatches;
        }

        Map<Long, TornStockPortfolioSlotDO> slotById = StockPortfolioService.indexSlotsById(snapshot.slots());

        for (TornStockVirtualBatchDO batch : exitPendingBatches) {
            processSingleExitBatch(batch, barByStock, slotById, roundTime, filledBatches);
        }
        for (TornStockVirtualBatchDO batch : staleExitBatches) {
            processSingleStaleExitBatch(batch, barByStock, slotById, filledBatches);
        }
        return filledBatches;
    }

    /**
     * 处理单个待卖出批次: 判断bar可用性与连续性, 决定成交、DATA_STALE_EXIT或等待。
     * <p>
     * 只有退出原因已通过fail-closed校验后才会结算槽位和写入CLOSED状态。
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
        if (!Stock15mBarBuildService.isUsable(currentBar)) {
            log.info("待卖出批次[{}]本轮bar不可用,转DATA_STALE_EXIT: stocksId={}",
                    batch.getBatchNo(), batch.getStocksId());
            transitionToDataStaleExit(batch);
            return;
        }

        // 检查bar连续性: 不连续说明已错过预期bar
        if (!isExitBarConsecutive(batch, currentBar)) {
            log.info("待卖出批次[{}]本轮bar非连续(已错过预期bar),转DATA_STALE_EXIT: expected={}, actual={}",
                    batch.getBatchNo(), batch.getExpectedExitBarTime(), currentBar.getBarStartTime());
            transitionToDataStaleExit(batch);
            return;
        }

        if (StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(batch.getLedgerType())) {
            fillShadowExitBatch(batch, currentBar, roundTime, filledBatches);
            return;
        }

        validateNormalSellSource(batch);
        fillExitBatch(batch, currentBar, slotById, roundTime, filledBatches);
        log.info("待卖出批次成交: batchNo={}, stocksId={}, exitPrice={}, closeType={}",
                batch.getBatchNo(), batch.getStocksId(), currentBar.getLastPrice(), batch.getExitReason());
    }

    /**
     * 校验正常槽位账本SELL结算前的来源状态与退出事实(P1-4)。
     * <p>
     * 正常SELL前置条件:
     * <ul>
     *   <li>ledgerType=FORMAL或SHADOW_FORMAL_CANDIDATE</li>
     *   <li>batchStatus=EXIT_PENDING</li>
     *   <li>exitSignalTime != null</li>
     *   <li>expectedExitBarTime != null</li>
     *   <li>exitReason 属于冻结正式策略关闭类型</li>
     * </ul>
     * 任一不满足直接抛 {@link IllegalStateException},由轮次事务整体回滚,
     * 批次不关闭、槽位不释放、资金不回笼、SELL通知为0。不自动补字段。
     *
     * @param batch 待正常卖出的槽位账本批次
     * @throws IllegalStateException 来源状态或退出事实不满足冻结前置时抛出
     */
    private void validateNormalSellSource(TornStockVirtualBatchDO batch) {
        if (!portfolioService.isSlotBackedLedger(batch.getLedgerType())) {
            throw new IllegalStateException("槽位卖出批次账本类型非法: batchNo=" + batch.getBatchNo()
                    + ", ledgerType=" + batch.getLedgerType());
        }
        if (!StockBatchStatusEnum.EXIT_PENDING.getCode().equals(batch.getBatchStatus())) {
            throw new IllegalStateException("槽位卖出来源状态非法,必须为EXIT_PENDING: batchNo="
                    + batch.getBatchNo() + ", batchStatus=" + batch.getBatchStatus());
        }
        if (batch.getExitSignalTime() == null) {
            throw new IllegalStateException("正式卖出批次缺少exitSignalTime,禁止结算: batchNo=" + batch.getBatchNo());
        }
        if (batch.getExpectedExitBarTime() == null) {
            throw new IllegalStateException("正式卖出批次缺少expectedExitBarTime,禁止结算: batchNo=" + batch.getBatchNo());
        }
        if (!FROZEN_STRATEGY_CLOSE_TYPES.contains(batch.getExitReason())) {
            throw new IllegalStateException("正式卖出退出原因不在冻结正式关闭类型内,禁止结算: batchNo="
                    + batch.getBatchNo() + ", exitReason=" + batch.getExitReason());
        }
    }

    /**
     * 校验灾难关闭结算前的来源状态与退出事实(P1-4)。
     * <p>
     * 灾难关闭前置条件:
     * <ul>
     *   <li>ledgerType=FORMAL或SHADOW_FORMAL_CANDIDATE</li>
     *   <li>batchStatus=DATA_STALE_EXIT</li>
     *   <li>exitSignalTime != null</li>
     *   <li>expectedExitBarTime != null</li>
     *   <li>originalExitReason != null 且属于合法原正式关闭类型(不得用exitReason替代)</li>
     * </ul>
     * 任一不满足直接抛 {@link IllegalStateException},由轮次事务整体回滚,
     * 批次不关闭、槽位不释放、资金不回笼、SELL通知为0。不自动补字段。
     *
     * @param batch 待灾难关闭的槽位账本批次
     * @throws IllegalStateException 来源状态或退出事实不满足冻结前置时抛出
     */
    private void validateDisasterCloseSource(TornStockVirtualBatchDO batch) {
        if (!portfolioService.isSlotBackedLedger(batch.getLedgerType())) {
            throw new IllegalStateException("灾难关闭批次账本类型非法: batchNo=" + batch.getBatchNo()
                    + ", ledgerType=" + batch.getLedgerType());
        }
        if (!StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(batch.getBatchStatus())) {
            throw new IllegalStateException("灾难关闭来源状态非法,必须为DATA_STALE_EXIT: batchNo="
                    + batch.getBatchNo() + ", batchStatus=" + batch.getBatchStatus());
        }
        if (batch.getExitSignalTime() == null) {
            throw new IllegalStateException("灾难关闭批次缺少exitSignalTime,禁止结算: batchNo=" + batch.getBatchNo());
        }
        if (batch.getExpectedExitBarTime() == null) {
            throw new IllegalStateException("灾难关闭批次缺少expectedExitBarTime,禁止结算: batchNo=" + batch.getBatchNo());
        }
        if (batch.getOriginalExitReason() == null) {
            throw new IllegalStateException("灾难关闭批次缺少originalExitReason,禁止结算: batchNo=" + batch.getBatchNo()
                    + ", exitReason=" + batch.getExitReason());
        }
        if (!FROZEN_STRATEGY_CLOSE_TYPES.contains(batch.getOriginalExitReason())) {
            throw new IllegalStateException("灾难关闭原退出原因不在合法正式关闭类型内,禁止结算: batchNo="
                    + batch.getBatchNo() + ", originalExitReason=" + batch.getOriginalExitReason());
        }
    }

    /**
     * 将批次迁移到DATA_STALE_EXIT状态,首次迁移时冻结原策略退出原因。
     * <p>
     * 仅在首次迁移时写入{@code originalExitReason = exitReason};批次已处于
     * DATA_STALE_EXIT或originalExitReason已存在时不得覆盖,保证灾难审计事实唯一。
     *
     * @param batch 待迁移批次
     */
    private void transitionToDataStaleExit(TornStockVirtualBatchDO batch) {
        if (batch.getOriginalExitReason() == null) {
            batch.setOriginalExitReason(batch.getExitReason());
        }
        batch.setBatchStatus(StockBatchStatusEnum.DATA_STALE_EXIT.getCode());
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
     * 处理单个数据陈旧卖出批次: 等待首个恢复可用bar并按独立灾难处置规则关闭。
     * <p>
     * 冻结的灾难处置口径:
     * <ul>
     *   <li>恢复bar必须是可用且价格为正的正式bar,不要求与原预期成交bar连续</li>
     *   <li>disasterExitPrice = 恢复bar.lastPrice,批次终态 ADMIN_CLOSED</li>
     *   <li>按0.1%卖出费真实回笼资金并在同一事务释放槽位</li>
     *   <li>冷却48小时且 resetObserved=false</li>
     *   <li>保留原 exitReason 与 expectedExitBarTime,不得把恢复bar伪装成原策略准时成交</li>
     * </ul>
     * 没有合法恢复价格时保持DATA_STALE_EXIT继续占槽并告警,禁止用成本、旧价或零价结算。
     *
     * @param batch         数据陈旧卖出批次
     * @param barByStock    按股票ID索引的bar映射
     * @param slotById      槽位ID索引映射
     * @param filledBatches 输出: 已完成灾难关闭的批次列表
     */
    private void processSingleStaleExitBatch(TornStockVirtualBatchDO batch,
                                             Map<Integer, TornStockMarketBar15mDO> barByStock,
                                             Map<Long, TornStockPortfolioSlotDO> slotById,
                                             List<TornStockVirtualBatchDO> filledBatches) {
        TornStockMarketBar15mDO recoveryBar = barByStock.get(batch.getStocksId());
        if (!isUsableRecoveryBar(recoveryBar)) {
            warnStaleExitNoRecoveryBar(batch);
            return;
        }
        disasterCloseBatch(batch, recoveryBar, slotById, filledBatches);
    }

    /**
     * 结构化运维告警: DATA_STALE_EXIT批次无合法恢复bar,保持状态继续占槽。
     * <p>
     * 同一批次每小时最多输出一次WARN,避免15分钟轮次日志噪声。字段包含批次、股票、
     * 预期成交时间和陈旧分钟数。不新增告警平台,仅供运维监控与后续人工关闭决策。
     *
     * @param batch 数据陈旧卖出批次
     */
    private void warnStaleExitNoRecoveryBar(TornStockVirtualBatchDO batch) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastWarnAt = lastStaleExitWarnAt.get(batch.getId());
        if (lastWarnAt != null
                && now.isBefore(lastWarnAt.plusHours(STALE_EXIT_WARN_INTERVAL_HOURS))) {
            return;
        }
        lastStaleExitWarnAt.put(batch.getId(), now);
        long staleMinutes = batch.getExpectedExitBarTime() != null
                ? java.time.Duration.between(batch.getExpectedExitBarTime(), now).toMinutes() : 0L;
        log.warn("数据陈旧卖出批次[{}]无合法恢复bar,保持DATA_STALE_EXIT继续占槽并告警: "
                        + "stocksId={}, expectedExitBarTime={}, staleDurationMinutes={}",
                batch.getBatchNo(), batch.getStocksId(), batch.getExpectedExitBarTime(), Math.max(0L, staleMinutes));
    }

    /**
     * 判断bar是否为可用的灾难处置恢复bar。
     * <p>
     * 要求bar非空、满足正式可用标准、构建版本匹配、最后价格为正值且起止时间完整。
     * 不要求该bar与原预期成交bar连续。
     *
     * @param recoveryBar 恢复bar
     * @return 可作为灾难处置参考价的bar返回true
     */
    private boolean isUsableRecoveryBar(TornStockMarketBar15mDO recoveryBar) {
        if (!Stock15mBarBuildService.isUsable(recoveryBar)) {
            return false;
        }
        if (recoveryBar.getLastPrice() == null || recoveryBar.getLastPrice().signum() <= 0) {
            return false;
        }
        if (recoveryBar.getBarStartTime() == null || recoveryBar.getBarEndTime() == null) {
            return false;
        }
        return Stock15mBarBuildService.BUILD_VERSION.equals(recoveryBar.getBuildVersion());
    }

    /**
     * 执行数据陈旧卖出批次的灾难关闭结算。
     * <p>
     * 按冻结口径将批次终态置为 ADMIN_CLOSED,以恢复bar.lastPrice作为管理关闭参考价。
     * 正式批次走统一正式账本校验(ledgerType=FORMAL、槽位绑定、数量/价格/余款一致)并
     * 真实回笼资金释放槽位;影子批次显式UNLIMITED_SHADOW,只计算理论收益不操作正式槽位;
     * 未知或空ledgerType一律fail-closed抛异常。冷却统一48小时,resetObserved=false。
     * <p>
     * 同时持久化完整管理关闭审计事实: originalExitReason、adminCloseReason、
     * recoveryBarStartTime/EndTime、staleExitDurationSeconds。
     *
     * @param batch         数据陈旧卖出批次
     * @param recoveryBar   恢复bar
     * @param slotById      槽位ID索引映射
     * @param filledBatches 输出: 已完成灾难关闭的批次列表
     */
    private void disasterCloseBatch(TornStockVirtualBatchDO batch,
                                    TornStockMarketBar15mDO recoveryBar,
                                    Map<Long, TornStockPortfolioSlotDO> slotById,
                                    List<TornStockVirtualBatchDO> filledBatches) {
        BigDecimal disasterExitPrice = recoveryBar.getLastPrice();
        boolean isShadow = StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(batch.getLedgerType());
        boolean isSlotBacked = portfolioService.isSlotBackedLedger(batch.getLedgerType());

        BigDecimal sellProceeds;
        if (isSlotBacked) {
            validateDisasterCloseSource(batch);
            TornStockPortfolioSlotDO slot = batch.getSlotId() != null ? slotById.get(batch.getSlotId()) : null;
            sellProceeds = portfolioService.settleFormalSlot(batch, slot, disasterExitPrice);
        } else if (isShadow) {
            sellProceeds = disasterExitPrice;
        } else {
            throw new IllegalStateException("灾难关闭批次账本类型非法,禁止非Shadow即正式: batchNo="
                    + batch.getBatchNo() + ", ledgerType=" + batch.getLedgerType());
        }

        BigDecimal netReturn = StockPortfolioService.calculateNetReturn(
                batch.getEntryReferencePrice(), disasterExitPrice);

        // 保留原exitReason与expectedExitBarTime,仅切换终态并落灾难参考价
        batch.setBatchStatus(StockBatchStatusEnum.ADMIN_CLOSED.getCode());
        batch.setExitReferencePrice(disasterExitPrice);
        batch.setExitTime(recoveryBar.getBarEndTime());
        batch.setNetReturn(netReturn);
        batch.setSellProceeds(sellProceeds);
        batch.setSellRuleVersion(StockRoundTransactionService.SELL_RULE_VERSION);
        batch.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);
        batch.setCooldownUntil(recoveryBar.getBarEndTime().plusHours(DISASTER_COOLDOWN_HOURS));
        batch.setResetObserved(false);
        writeDisasterCloseAudit(batch, recoveryBar);

        filledBatches.add(batch);
        log.info("数据陈旧卖出批次灾难关闭: batchNo={}, disasterExitPrice={}, exitTime={}, originalExitReason={}, expectedExitBarTime={}",
                batch.getBatchNo(), disasterExitPrice, recoveryBar.getBarEndTime(),
                batch.getOriginalExitReason(), batch.getExpectedExitBarTime());
    }

    /**
     * 持久化灾难关闭完整审计事实。
     * <p>
     * 仅当批次已置为ADMIN_CLOSED且adminCloseReason固定为DATA_STALE_EXIT_RECOVERY_CLOSE时,
     * 写入originalExitReason(已冻结不可覆盖)、recoveryBarStartTime/EndTime以及
     * staleExitDurationSeconds = max(0, recoveryBarEnd - (expectedExitBarTime + 15分钟))。
     *
     * @param batch       已置ADMIN_CLOSED的批次
     * @param recoveryBar 使用的恢复bar
     */
    private void writeDisasterCloseAudit(TornStockVirtualBatchDO batch,
                                         TornStockMarketBar15mDO recoveryBar) {
        if (batch.getOriginalExitReason() == null) {
            batch.setOriginalExitReason(batch.getExitReason());
        }
        batch.setAdminCloseReason(ADMIN_CLOSE_REASON_DISASTER_RECOVERY);
        batch.setRecoveryBarStartTime(recoveryBar.getBarStartTime());
        batch.setRecoveryBarEndTime(recoveryBar.getBarEndTime());
        batch.setStaleExitDurationSeconds(calculateStaleExitDurationSeconds(
                batch.getExpectedExitBarTime(), recoveryBar.getBarEndTime()));
    }

    /**
     * 计算数据陈旧退出持续时间(秒)。
     * <p>
     * staleExitDurationSeconds = max(0, recoveryBarEnd - (expectedExitBarTime + 15分钟)),
     * 从原预期卖出bar结束到恢复bar结束。
     *
     * @param expectedExitBarTime 原预期卖出bar时间
     * @param recoveryBarEndTime  恢复bar结束时间
     * @return 陈旧持续秒数,不小于0;入参缺失时返回0
     */
    private long calculateStaleExitDurationSeconds(LocalDateTime expectedExitBarTime,
                                                   LocalDateTime recoveryBarEndTime) {
        if (expectedExitBarTime == null || recoveryBarEndTime == null) {
            return 0L;
        }
        long durationSeconds = java.time.Duration.between(
                expectedExitBarTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES),
                recoveryBarEndTime).getSeconds();
        return Math.max(0L, durationSeconds);
    }

    /**
     * 成交无限资金影子批次的理论平仓,不结算正式槽位。
     *
     * @param batch         待成交影子批次
     * @param currentBar    当前连续bar
     * @param roundTime     本轮时间
     * @param filledBatches 输出: 已成交影子批次列表
     */
    private void fillShadowExitBatch(TornStockVirtualBatchDO batch,
                                     TornStockMarketBar15mDO currentBar,
                                     LocalDateTime roundTime,
                                     List<TornStockVirtualBatchDO> filledBatches) {
        BigDecimal exitReferencePrice = currentBar.getLastPrice();
        BigDecimal netReturn = StockPortfolioService.calculateNetReturn(
                batch.getEntryReferencePrice(), exitReferencePrice);
        batch.setBatchStatus(resolveCloseStatus(batch.getExitReason()).getCode());
        batch.setExitReferencePrice(exitReferencePrice);
        batch.setExitTime(roundTime);
        batch.setNetReturn(netReturn);
        batch.setSellProceeds(exitReferencePrice);
        batch.setCooldownUntil(calculateCooldownUntil(
                resolveCloseTypeEnum(batch.getExitReason()), roundTime));
        batch.setResetObserved(false);
        if (isFilledCloseStatus(batch.getBatchStatus())) {
            filledBatches.add(batch);
        }
    }

    /**
     * 平仓后根据关闭类型设置冷却期与复位标记。
     *
     * @param batch         待成交批次
     * @param currentBar    当前bar
     * @param slotById      槽位索引
     * @param roundTime     平仓时间
     * @param filledBatches 已成交批次列表
     */
    private void fillExitBatch(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar,
                               Map<Long, TornStockPortfolioSlotDO> slotById,
                               LocalDateTime roundTime,
                               List<TornStockVirtualBatchDO> filledBatches) {
        BigDecimal exitReferencePrice = currentBar.getLastPrice();

        StockCloseTypeEnum closeTypeEnum = resolveCloseTypeEnum(batch.getExitReason());
        StockBatchStatusEnum closeStatus = mapCloseTypeToBatchStatus(closeTypeEnum);

        TornStockPortfolioSlotDO slot = batch.getSlotId() != null ? slotById.get(batch.getSlotId()) : null;
        BigDecimal sellProceeds = portfolioService.settleFormalSlot(batch, slot, exitReferencePrice);

        // 计算净收益
        BigDecimal netReturn = StockPortfolioService.calculateNetReturn(
                batch.getEntryReferencePrice(), exitReferencePrice);

        batch.setBatchStatus(closeStatus.getCode());
        batch.setExitReferencePrice(exitReferencePrice);
        batch.setExitTime(roundTime);
        batch.setNetReturn(netReturn);
        batch.setSellProceeds(sellProceeds);
        batch.setSellRuleVersion(StockRoundTransactionService.SELL_RULE_VERSION);
        batch.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);

        // 设置冷却时间: 风险关闭48小时, 其他关闭24小时
        batch.setCooldownUntil(calculateCooldownUntil(closeTypeEnum, roundTime));
        // 平仓后复位标记置为false,要求观察到买入条件复位后才能再次产生信号
        batch.setResetObserved(false);

        if (!isFilledCloseStatus(batch.getBatchStatus())) {
            return;
        }
        filledBatches.add(batch);
    }

    private StockBatchStatusEnum resolveCloseStatus(String exitReason) {
        return mapCloseTypeToBatchStatus(resolveCloseTypeEnum(exitReason));
    }

    /**
     * 解析关闭类型枚举,空值或未知编码均视为数据一致性错误。
     *
     * @param exitReason 卖出原因编码
     * @return 关闭类型枚举
     * @throws IllegalStateException 关闭原因为空或无法解析时抛出
     */
    private StockCloseTypeEnum resolveCloseTypeEnum(String exitReason) {
        if (exitReason == null || exitReason.isBlank()) {
            throw new IllegalStateException("关闭类型编码为空,数据一致性破坏");
        }
        return safeParseCloseType(exitReason);
    }

    /**
     * 安全解析关闭类型枚举,解析失败时fail-closed抛出数据一致性异常。
     * <p>
     * 不把未知编码回退为CLOSED_TARGET,避免把数据损坏伪装成目标退出。
     *
     * @param code 关闭类型编码
     * @return 关闭类型枚举
     * @throws IllegalStateException 编码无法解析时抛出
     */
    private StockCloseTypeEnum safeParseCloseType(String code) {
        try {
            return StockCloseTypeEnum.fromCode(code);
        } catch (IllegalArgumentException e) {
            log.error("关闭类型编码解析失败,fail-closed: code={}", code);
            throw new IllegalStateException("关闭类型编码无法解析,数据一致性破坏: " + code, e);
        }
    }

    /**
     * 判断批次是否为真实关闭状态。
     *
     * @param batchStatus 批次状态编码
     * @return CLOSED_*或ADMIN_CLOSED时返回true
     */
    private boolean isFilledCloseStatus(String batchStatus) {
        if (batchStatus == null) {
            return false;
        }
        return List.of(FILLED_CLOSE_STATUSES).contains(batchStatus);
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

    /**
     * 计算平仓后冷却截止时间。
     * <p>
     * 风险关闭(CLOSED_RISK)使用48小时冷却期,其他关闭类型使用24小时冷却期。
     * 冷却期内同股不再产生同类买入信号。
     *
     * @param closeType 关闭类型
     * @param exitTime  平仓时间
     * @return 冷却截止时间
     */
    private LocalDateTime calculateCooldownUntil(StockCloseTypeEnum closeType, LocalDateTime exitTime) {
        int cooldownHours = closeType == StockCloseTypeEnum.CLOSED_RISK
                ? RISK_COOLDOWN_HOURS : NORMAL_COOLDOWN_HOURS;
        return exitTime.plusHours(cooldownHours);
    }

    // ==================== 辅助方法 ====================

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
