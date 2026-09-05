package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaTargetPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * α策略初始入场服务，将已持久化的PENDING初始决策转换为VIP_ALPHA单槽待入场批次。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaEntryService {
    private static final String PENDING_STATUS = "PENDING";
    private static final String EXECUTED_STATUS = "EXECUTED";
    private static final String ALPHA_PRIMARY_STRATEGY = "ALPHA";

    private final TornStockAlphaDecisionDAO decisionDAO;
    private final TornStockVirtualBatchDAO virtualBatchDAO;
    private final StockPortfolioService portfolioService;
    private final StockShadowRecordWriter noticeWriter;

    /**
     * 消费当前执行轮次对应的初始α决策。
     *
     * @param roundTime    当前执行桶
     * @param snapshot     当前轮次快照
     * @param decisionDate 决策日期
     * @param phase        决策阶段
     * @return 初始入场批次；没有待消费决策时返回null
     */
    @Transactional(rollbackFor = Exception.class)
    public TornStockVirtualBatchDO createInitialEntry(LocalDateTime roundTime, RoundSnapshot snapshot,
                                                      LocalDate decisionDate, int phase) {
        Objects.requireNonNull(roundTime, "轮次时间不能为空");
        Objects.requireNonNull(snapshot, "轮次快照不能为空");
        TornStockAlphaDecisionDO decision = decisionDAO.selectPendingInitialEntryForUpdate(
                decisionDate, phase, roundTime);
        TornStockVirtualBatchDO activeBatch = findActiveAlphaBatch(snapshot);
        if (isExecutedInitialDecision(decision)) {
            validateBatchAssociation(activeBatch, decision);
            return activeBatch;
        }
        if (!isPendingInitialDecision(decision)) {
            return null;
        }
        validateExecutionBar(decision, decisionDate, phase, roundTime, snapshot);


        if (activeBatch != null) {
            validateBatchAssociation(activeBatch, decision);
            markExecuted(decision, activeBatch);
            return activeBatch;
        }
        TornStockPortfolioSlotDO slot = findAvailableAlphaSlot(snapshot.slots());
        TornStockMarketBar15mDO bar = requireExecutionBar(snapshot, decision.getSelectedStocksId(), roundTime);
        validateFunds(slot, bar.getLastPrice());
        TornStockVirtualBatchDO batch = buildBatch(decision, slot, bar, roundTime);
        if (virtualBatchDAO.insertIgnoreConflict(batch) != 1) {
            throw new IllegalStateException("Alpha初始批次插入冲突: batchNo=" + batch.getBatchNo());
        }
        TornStockVirtualBatchDO persisted = virtualBatchDAO.selectByBatchNoForUpdate(batch.getBatchNo());
        validatePersistedBatch(persisted, batch, decision, slot);
        portfolioService.reserveSlot(slot, slot.getAvailableCash(), persisted.getId());
        markExecuted(decision, persisted);
        log.info("Alpha初始批次创建: decisionDate={}, stocksId={}, batchNo={}, slotNo={}",
                decisionDate, persisted.getStocksId(), persisted.getBatchNo(), slot.getSlotNo());
        noticeWriter.writeNoticeAudits(List.of(persisted), List.of(), roundTime);
        return persisted;
    }

    private boolean isExecutedInitialDecision(TornStockAlphaDecisionDO decision) {
        return decision != null && EXECUTED_STATUS.equals(decision.getExecutionStatus())
                && StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY.name().equals(decision.getDecisionType())
                && decision.getCurrentBatchId() != null;
    }

    /**
     * 判断是否为待消费的初始α决策。
     *
     * @param decision 决策记录
     * @return 是则返回true
     */
    private boolean isPendingInitialDecision(TornStockAlphaDecisionDO decision) {
        return decision != null && PENDING_STATUS.equals(decision.getExecutionStatus())
                && StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY.name().equals(decision.getDecisionType())
                && decision.getSelectedStocksId() != null;
    }

    /**
     * 校验决策只能在严格下一执行bar消费。
     *
     * @param decision  决策记录
     * @param roundTime 当前轮次时间
     * @param snapshot  当前轮次快照
     */
    private void validateExecutionBar(TornStockAlphaDecisionDO decision, LocalDate decisionDate, int phase,
                                      LocalDateTime roundTime, RoundSnapshot snapshot) {
        LocalDateTime expected = decision.getExecutionBarStartTime();
        TornStockMarketBar15mDO bar = findBar(snapshot, decision.getSelectedStocksId(), expected);
        if (decision.getId() == null || decision.getDecisionBusinessDate() == null
                || !decisionDate.equals(decision.getDecisionBusinessDate())
                || decision.getPhase() == null || !Integer.valueOf(phase).equals(decision.getPhase())
                || decision.getSelectedStocksId() == null

                || bar == null || bar.getBarStartTime() == null || bar.getBarEndTime() == null
                || bar.getStocksShortname() == null || bar.getStocksShortname().isBlank()) {
            throw new IllegalStateException("Alpha初始入场决策或执行bar关键字段缺失");
        }
        if (!roundTime.equals(expected) || !expected.equals(bar.getBarStartTime())
                || !StockAlphaExecutionBarPolicy.isExecutable(
                roundTime.minusMinutes(15), toExecutionBar(bar), roundTime.plusMinutes(15))) {
            throw new IllegalStateException("Alpha初始入场bar不是严格下一根可执行bar: roundTime=" + roundTime);
        }
    }

    /**
     * 查找唯一VIP_ALPHA活跃批次。
     *
     * @param snapshot 当前轮次快照
     * @return 活跃批次；不存在时返回null
     */
    private TornStockVirtualBatchDO findActiveAlphaBatch(RoundSnapshot snapshot) {
        return snapshot.activeBatches().stream()
                .filter(batch -> StockLedgerTypeEnum.VIP_ALPHA.getCode().equals(batch.getLedgerType()))
                .findFirst().orElse(null);
    }

    /**
     * 查找唯一Alpha槽位。
     *
     * @param slots 全部锁定槽位
     * @return 可用槽位
     */
    private TornStockPortfolioSlotDO findAvailableAlphaSlot(List<TornStockPortfolioSlotDO> slots) {

        List<TornStockPortfolioSlotDO> available = slots.stream()
                .filter(slot -> StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE.equals(slot.getPortfolioCode()))
                .filter(slot -> Integer.valueOf(1).equals(slot.getSlotNo()))
                .filter(slot -> StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .toList();
        if (available.size() != StockPortfolioService.VIP_ALPHA_SLOT_COUNT) {
            throw new IllegalStateException("VIP_ALPHA槽位不可用或数量异常");
        }
        return available.getFirst();
    }

    /**
     * 要求存在可执行bar。
     *
     * @param snapshot  当前快照
     * @param stocksId  股票ID
     * @param roundTime 执行时间
     * @return 执行bar
     */
    private TornStockMarketBar15mDO requireExecutionBar(RoundSnapshot snapshot, Integer stocksId,
                                                        LocalDateTime roundTime) {
        TornStockMarketBar15mDO bar = findBar(snapshot, stocksId, roundTime);
        if (bar == null) {
            throw new IllegalStateException("Alpha执行bar缺失: stocksId=" + stocksId + ", roundTime=" + roundTime);
        }
        return bar;
    }

    /**
     * 校验持久化批次与预期来源一致。
     *
     * @param persisted 持久化批次
     * @param expected  预期批次
     * @param decision  来源决策
     * @param slot      目标槽位
     */
    private void validatePersistedBatch(TornStockVirtualBatchDO persisted, TornStockVirtualBatchDO expected,
                                        TornStockAlphaDecisionDO decision, TornStockPortfolioSlotDO slot) {
        if (persisted == null || persisted.getId() == null || persisted.getAlphaDecisionId() == null
                || !persisted.getAlphaDecisionId().equals(decision.getId())
                || !Objects.equals(persisted.getSlotId(), slot.getId())
                || !Objects.equals(persisted.getStocksId(), expected.getStocksId())
                || !StockBatchStatusEnum.ENTRY_PENDING.getCode().equals(persisted.getBatchStatus())) {
            throw new IllegalStateException("Alpha初始批次持久化字段或关联非法: batchNo=" + expected.getBatchNo());
        }
    }

    /**
     * 校验已有活跃批次属于当前决策。
     *
     * @param batch    活跃批次
     * @param decision α决策
     */
    private void validateBatchAssociation(TornStockVirtualBatchDO batch, TornStockAlphaDecisionDO decision) {
        if (batch.getId() == null || !decision.getId().equals(batch.getAlphaDecisionId())
                || !decision.getSelectedStocksId().equals(batch.getStocksId())) {
            throw new IllegalStateException("Alpha活跃批次与决策关联不一致");
        }
    }

    /**
     * 查找当前股票的执行bar。
     *
     * @param snapshot  当前轮次快照
     * @param stocksId  股票ID
     * @param roundTime 执行bar起点
     * @return 执行bar；不存在时返回null
     */
    private TornStockMarketBar15mDO findBar(RoundSnapshot snapshot, Integer stocksId, LocalDateTime roundTime) {

        return snapshot.bars().stream()
                .filter(bar -> Objects.equals(stocksId, bar.getStocksId())
                        && Objects.equals(roundTime, bar.getBarStartTime()))
                .findFirst().orElse(null);
    }

    /**
     * 校验Alpha单槽资金足以买入一股。
     *
     * @param slot  Alpha槽位
     * @param price 执行价格
     */
    private void validateFunds(TornStockPortfolioSlotDO slot, BigDecimal price) {
        if (price == null || price.signum() <= 0
                || StockPortfolioService.calculateQuantity(slot.getAvailableCash(), price) <= 0) {
            throw new IllegalStateException("VIP_ALPHA槽位资金不足买入一股");
        }
    }

    /**
     * 创建Alpha初始待入场批次。
     *
     * @param decision  α决策
     * @param slot      Alpha槽位
     * @param bar       执行bar
     * @param roundTime 执行时间
     * @return 未持久化批次
     */
    private TornStockVirtualBatchDO buildBatch(TornStockAlphaDecisionDO decision,
                                               TornStockPortfolioSlotDO slot,
                                               TornStockMarketBar15mDO bar,
                                               LocalDateTime roundTime) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo("A" + decision.getDecisionBusinessDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + decision.getPhase());
        batch.setLedgerType(StockLedgerTypeEnum.VIP_ALPHA.getCode());
        batch.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        batch.setStocksId(decision.getSelectedStocksId());
        batch.setStocksShortname(bar.getStocksShortname());
        batch.setPrimaryStrategy(ALPHA_PRIMARY_STRATEGY);
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSlotId(slot.getId());
        batch.setSlotNo(slot.getSlotNo());
        batch.setSignalTime(roundTime.minusMinutes(15));
        batch.setSignalReferencePrice(bar.getLastPrice());
        batch.setExpectedEntryBarTime(roundTime);
        batch.setEntryStaleAt(roundTime.plusMinutes(20));
        batch.setMatchedStrategies("[\"ALPHA\"]");
        batch.setQualityScore(BigDecimal.ZERO);
        batch.setSignalEventId(null);
        batch.setAlphaDecisionId(decision.getId());
        batch.setStylePrior("NOT_APPLICABLE");
        batch.setStyleMaturity("NOT_APPLICABLE");
        batch.setRiskLevel("NOT_APPLICABLE");
        batch.setStyleEffectiveMonth(decision.getDecisionBusinessDate().withDayOfMonth(1));
        batch.setStyleRuleVersion("ALPHA_NOT_APPLICABLE");
        batch.setRiskRuleVersion("ALPHA_NOT_APPLICABLE");
        batch.setAllocationRuleVersion("ALPHA_100_PERCENT");
        batch.setMessageRuleVersion("ALPHA_V1");
        batch.setBuyRuleVersion("ALPHA_0.04_V1");
        batch.setSellRuleVersion("ALPHA_REBALANCE_ONLY");
        batch.setResetObserved(false);
        return batch;
    }

    /**
     * 将决策标记为已执行并关联批次。
     *
     * @param decision α决策
     * @param batch    已创建批次
     */
    private void markExecuted(TornStockAlphaDecisionDO decision, TornStockVirtualBatchDO batch) {
        decision.setExecutionStatus(EXECUTED_STATUS);
        decision.setCurrentBatchId(batch == null ? null : batch.getId());
        decision.setFailureReason(null);
        decisionDAO.updateById(decision);
    }

    /**
     * 转换公共执行bar值对象。
     *
     * @param bar 数据库bar
     * @return 执行bar值对象
     */
    private StockAlphaExecutionBarPolicy.ExecutionBar toExecutionBar(TornStockMarketBar15mDO bar) {
        return bar == null ? null : new StockAlphaExecutionBarPolicy.ExecutionBar(
                bar.getBarStartTime(), bar.getBarEndTime(), Boolean.TRUE.equals(bar.getUsable()), bar.getLastPrice());
    }
}
