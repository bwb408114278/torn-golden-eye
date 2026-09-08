package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaTargetPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockVirtualBatchAssembler;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * α策略原子换仓服务。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaRebalanceService {
    private static final String PENDING_STATUS = "PENDING";
    private static final String EXECUTED_STATUS = "EXECUTED";
    private static final String ALPHA_REBALANCE = "ALPHA_REBALANCE";
    private static final String ALPHA_REBALANCE_RULE_VERSION = "ALPHA_REBALANCE_ONLY";
    private static final String ALPHA_PRIMARY_STRATEGY = "ALPHA";

    private final TornStockAlphaDecisionDAO decisionDAO;
    private final TornStockPortfolioSlotDAO slotDAO;
    private final TornStockVirtualBatchDAO batchDAO;
    private final StockPortfolioService portfolioService;
    private final StockShadowRecordWriter noticeWriter;

    /**
     * 在同一事务内完成α原仓SELL与新仓BUY。
     * <p>
     * 决策锁定键固定为"决策业务日 + phase + 当前轮次执行桶":持久化执行桶与当前轮次不一致时
     * 读不到决策,直接fail-closed跳过,不换仓、不写批次、不发送通知,也不跨桶追补,
     * 更不抛出异常把轮次钉死在可重试失败状态。
     *
     * @param decisionDate 决策日期
     * @param phase        决策阶段
     * @param now          当前校验时点
     * @param snapshot     当前轮次行情快照
     * @return 换仓结果;未消费到同执行桶决策时三个分量均为null
     */
    @Transactional(rollbackFor = Exception.class)
    public RebalanceResult rebalance(LocalDate decisionDate, int phase, LocalDateTime now,
                                     RoundSnapshot snapshot) {
        LocalDateTime roundTime = snapshot.roundTime();
        TornStockAlphaDecisionDO decision = decisionDAO.selectByExecutionKeyForUpdate(decisionDate, phase, roundTime);
        if (decision == null) {
            log.warn("α换仓未读到同执行桶的持久化决策,本次跳过换仓: decisionDate={}, phase={}, roundTime={}",
                    decisionDate, phase, roundTime);
            return new RebalanceResult(null, null, null);
        }
        if (isExecutedRebalance(decision)) {
            return new RebalanceResult(null, decision.getRebalanceBatchId(), decision.getExecutionBarStartTime());
        }
        TornStockPortfolioSlotDO slot = lockAlphaSlot();
        List<TornStockVirtualBatchDO> batches = batchDAO.selectActiveAlphaBatchesForUpdate();
        TornStockVirtualBatchDO current = findOpenBatch(batches);
        validateDecision(decision, decisionDate, phase, current, slot);
        LocalDateTime executionBarStart =
                StockAlphaExecutionBarPolicy.requireExecutionBar(decision.getExecutionBarStartTime());

        TornStockMarketBar15mDO sellBar = findBar(snapshot, current.getStocksId(), executionBarStart);
        TornStockMarketBar15mDO buyBar = findBar(snapshot, decision.getSelectedStocksId(), executionBarStart);
        if (sellBar == null || buyBar == null) {
            throw new IllegalStateException("α换仓执行bar缺失");
        }
        validateBars(executionBarStart, now, sellBar, buyBar);
        current.setExitSignalTime(executionBarStart);
        current.setExpectedExitBarTime(sellBar.getBarStartTime());
        TornStockVirtualBatchDO replacement = replace(current, decision, slot, sellBar, buyBar, executionBarStart, executionBarStart);
        TornStockVirtualBatchDO persistedReplacement = persistReplacement(replacement);
        bindCompletedRebalance(decision, slot, persistedReplacement, executionBarStart);
        batchDAO.updateById(current);
        batchDAO.updateById(persistedReplacement);
        decisionDAO.updateById(decision);
        slotDAO.updateById(slot);
        noticeWriter.writeNoticeAudits(List.of(persistedReplacement), List.of(current), executionBarStart, true);
        return new RebalanceResult(current.getId(), persistedReplacement.getId(), executionBarStart);
    }

    private boolean isExecutedRebalance(TornStockAlphaDecisionDO decision) {
        return decision != null && EXECUTED_STATUS.equals(decision.getExecutionStatus())
                && StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED.name().equals(decision.getDecisionType())
                && decision.getRebalanceBatchId() != null && decision.getExecutionBarStartTime() != null;
    }

    /**
     * 锁定唯一VIP_ALPHA槽位并校验其当前绑定。
     *
     * @return 已锁定槽位
     */
    private TornStockPortfolioSlotDO lockAlphaSlot() {
        List<TornStockPortfolioSlotDO> slots = slotDAO.selectAllByPortfolioCodeForUpdate(
                StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        if (slots.size() != StockPortfolioService.VIP_ALPHA_SLOT_COUNT
                || !StockSlotStatusEnum.OCCUPIED.getCode().equals(slots.getFirst().getSlotStatus())) {
            throw new IllegalStateException("VIP_ALPHA槽位不可用于原子换仓");
        }
        return slots.getFirst();
    }

    /**
     * 校验决策为合法换仓决策。
     *
     * @param decision 决策记录
     * @param current  当前批次
     */
    private void validateDecision(TornStockAlphaDecisionDO decision, LocalDate decisionDate, int phase,
                                  TornStockVirtualBatchDO current, TornStockPortfolioSlotDO slot) {
        if (decision == null || !PENDING_STATUS.equals(decision.getExecutionStatus())
                || !StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED.name().equals(decision.getDecisionType())
                || decision.getId() == null || !decisionDate.equals(decision.getDecisionBusinessDate())
                || !Integer.valueOf(phase).equals(decision.getPhase()) || decision.getSelectedStocksId() == null
                || current == null || !Objects.equals(decision.getCurrentBatchId(), current.getId())
                || !Objects.equals(slot.getCurrentBatchId(), current.getId())
                || decision.getSelectedStocksId().equals(current.getStocksId())) {
            throw new IllegalStateException("α换仓决策非法或当前持仓不一致");
        }
    }

    /**
     * 校验两腿必须使用同一持久化执行桶。
     *
     * @param executionBarStart 持久化执行bar起点
     * @param now               当前校验时点
     * @param sellBar           原仓bar
     * @param buyBar            新仓bar
     */
    private void validateBars(LocalDateTime executionBarStart, LocalDateTime now,
                              TornStockMarketBar15mDO sellBar, TornStockMarketBar15mDO buyBar) {
        if (sellBar == null || buyBar == null) {
            throw new IllegalStateException("α换仓执行bar缺失");
        }
        if (!StockAlphaExecutionBarPolicy.isAtomicRebalance(executionBarStart,
                toExecutionBar(sellBar), toExecutionBar(buyBar), now)) {
            throw new IllegalStateException("α换仓两腿不存在同一持久化可用15m执行桶");
        }
    }

    /**
     * 执行原仓结算并构造新仓成交事实。
     *
     * @param current           原仓批次
     * @param decision          决策记录
     * @param slot              α槽位
     * @param sellBar           原仓bar
     * @param buyBar            新仓bar
     * @param executionBarStart 执行bar起点
     * @param executionTime     执行时间
     * @return 新仓批次
     */
    private TornStockVirtualBatchDO replace(TornStockVirtualBatchDO current, TornStockAlphaDecisionDO decision,
                                            TornStockPortfolioSlotDO slot, TornStockMarketBar15mDO sellBar,
                                            TornStockMarketBar15mDO buyBar, LocalDateTime executionBarStart,
                                            LocalDateTime executionTime) {
        BigDecimal sellProceeds = portfolioService.settleSlotBacked(current, slot, sellBar.getLastPrice(),
                StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        current.setSellProceeds(sellProceeds);
        current.setNetReturn(StockPortfolioService.calculateNetReturn(current.getEntryReferencePrice(), sellBar.getLastPrice()));
        current.setBatchStatus(StockBatchStatusEnum.CLOSED_ROTATION.getCode());
        current.setExitTime(executionBarStart);
        current.setExitReferencePrice(sellBar.getLastPrice());
        current.setExitReason(ALPHA_REBALANCE);
        current.setSellRuleVersion(ALPHA_REBALANCE_RULE_VERSION);
        BigDecimal cash = slot.getAvailableCash();
        long quantity = StockPortfolioService.calculateQuantity(cash, buyBar.getLastPrice());
        if (quantity <= 0) {
            throw new IllegalStateException("VIP_ALPHA槽位资金不足买入新仓");
        }
        TornStockVirtualBatchDO replacement = new TornStockVirtualBatchDO();
        replacement.setBatchNo(buildReplacementBatchNo(decision));
        replacement.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        replacement.setPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        replacement.setStocksId(decision.getSelectedStocksId());
        replacement.setStocksShortname(buyBar.getStocksShortname());
        replacement.setPrimaryStrategy(ALPHA_PRIMARY_STRATEGY);
        replacement.setMatchedStrategies("[\"ALPHA\"]");
        replacement.setQualityScore(BigDecimal.ZERO);
        replacement.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        replacement.setAlphaDecisionId(decision.getId());
        replacement.setSlotId(slot.getId());
        replacement.setSlotNo(slot.getSlotNo());
        replacement.setSignalTime(executionTime);
        replacement.setSignalReferencePrice(buyBar.getLastPrice());
        replacement.setExpectedEntryBarTime(buyBar.getBarStartTime());
        replacement.setEntryTime(buyBar.getBarStartTime());
        replacement.setEntryReferencePrice(buyBar.getLastPrice());
        replacement.setQuantity(quantity);
        replacement.setInvestedCash(buyBar.getLastPrice().multiply(BigDecimal.valueOf(quantity)));
        replacement.setRemainingCash(cash.subtract(replacement.getInvestedCash()));
        replacement.setStylePrior(StockStrategyFitEnum.ALPHA_NOT_EVALUATED.getCode());
        replacement.setStyleMaturity(StockMaturityEnum.ALPHA_NOT_EVALUATED.getCode());
        replacement.setRiskLevel(StockRiskLevelEnum.ALPHA_NOT_EVALUATED.getCode());
        replacement.setStyleEffectiveMonth(executionTime.toLocalDate().withDayOfMonth(1));
        replacement.setBuyRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        replacement.setSellRuleVersion(ALPHA_REBALANCE_RULE_VERSION);
        replacement.setStyleRuleVersion(StockAlphaRuleDefinition.STYLE_RULE_VERSION);
        replacement.setRiskRuleVersion(StockAlphaRuleDefinition.RISK_RULE_VERSION);
        replacement.setAllocationRuleVersion("ALPHA_100_PERCENT");
        replacement.setMessageRuleVersion("ALPHA_V1");
        replacement.setExpectedExitBarTime(buyBar.getBarEndTime());
        replacement.setResetObserved(false);
        applyFilledEntryFields(replacement, slot, buyBar, quantity, cash);
        return replacement;
    }

    private void applyFilledEntryFields(TornStockVirtualBatchDO replacement, TornStockPortfolioSlotDO slot,
                                        TornStockMarketBar15mDO buyBar, long quantity, BigDecimal cash) {
        TornStockVirtualBatchEntryFields fields = new TornStockVirtualBatchEntryFields();
        fields.setEntryReferencePrice(buyBar.getLastPrice());
        fields.setEntryTime(buyBar.getBarStartTime());
        fields.setQuantity(quantity);
        fields.setInvestedCash(buyBar.getLastPrice().multiply(BigDecimal.valueOf(quantity)));
        fields.setRemainingCash(cash.subtract(fields.getInvestedCash()));
        StockVirtualBatchAssembler.applyFilledEntryFields(replacement, fields);
        replacement.setBuyRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        replacement.setSellRuleVersion(ALPHA_REBALANCE_RULE_VERSION);
        replacement.setAllocationRuleVersion("ALPHA_100_PERCENT");
        replacement.setMessageRuleVersion("ALPHA_V1");
        slot.setAvailableCash(replacement.getRemainingCash());
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
    }

    /**
     * 生成决策唯一的换仓批次编号。
     *
     * @param decision α决策
     * @return 唯一批次编号
     */
    private String buildReplacementBatchNo(TornStockAlphaDecisionDO decision) {
        return "AR-" + decision.getDecisionBusinessDate() + "-" + decision.getPhase() + "-" + decision.getId();
    }

    /**
     * 插入后按业务编号复读批次并校验数据库ID，拒绝重复批次编号。
     *
     * @param replacement 待插入批次
     * @return 已持久化批次
     */
    private TornStockVirtualBatchDO persistReplacement(TornStockVirtualBatchDO replacement) {
        TornStockVirtualBatchDO existing = batchDAO.selectByBatchNoForUpdate(replacement.getBatchNo());
        if (existing != null) {
            throw new IllegalStateException("α换仓批次编号已存在: batchNo=" + replacement.getBatchNo());
        }
        if (batchDAO.insertIgnoreConflict(replacement) != 1) {
            throw new IllegalStateException("α换仓批次编号已存在或插入失败: batchNo=" + replacement.getBatchNo());
        }
        TornStockVirtualBatchDO persisted = batchDAO.selectByBatchNoForUpdate(replacement.getBatchNo());
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("α换仓批次插入后无法读取有效ID: batchNo=" + replacement.getBatchNo());
        }
        return persisted;
    }

    /**
     * 查找唯一开放α批次。
     *
     * @param batches 活跃批次
     * @return 开放批次
     */
    private TornStockVirtualBatchDO findOpenBatch(List<TornStockVirtualBatchDO> batches) {
        List<TornStockVirtualBatchDO> open = batches.stream()
                .filter(StockPortfolioService::isAlphaBatch)
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus())).toList();
        if (open.size() != 1) {
            throw new IllegalStateException("VIP_ALPHA当前持仓批次数量异常");
        }
        return open.getFirst();
    }

    /**
     * 查找指定执行桶行情。
     *
     * @param snapshot 轮次快照
     * @param stocksId 股票ID
     * @param time     执行桶起点
     * @return 行情bar
     */
    private TornStockMarketBar15mDO findBar(RoundSnapshot snapshot, Integer stocksId, LocalDateTime time) {
        return snapshot.bars().stream().filter(bar -> stocksId.equals(bar.getStocksId())
                && time.equals(bar.getBarStartTime())).findFirst().orElse(null);
    }

    /**
     * 绑定完成换仓后的决策、槽位和批次关系。
     *
     * @param decision          换仓决策
     * @param slot              α策略槽位
     * @param replacement       新批次
     * @param executionBarStart 执行桶起点
     */
    private void bindCompletedRebalance(TornStockAlphaDecisionDO decision, TornStockPortfolioSlotDO slot,
                                        TornStockVirtualBatchDO replacement, LocalDateTime executionBarStart) {
        slot.setCurrentBatchId(replacement.getId());
        decision.setCurrentBatchId(replacement.getId());
        decision.setRebalanceBatchId(replacement.getId());
        decision.setExecutionBarStartTime(executionBarStart);
        decision.setExecutionStatus(EXECUTED_STATUS);
        decision.setFailureReason(null);
    }

    /**
     * 转换执行bar。
     *
     * @param bar 数据库bar
     * @return 执行bar
     */
    private StockAlphaExecutionBarPolicy.ExecutionBar toExecutionBar(TornStockMarketBar15mDO bar) {
        return bar == null ? null : new StockAlphaExecutionBarPolicy.ExecutionBar(bar.getBarStartTime(),
                bar.getBarEndTime(), Boolean.TRUE.equals(bar.getUsable()), bar.getLastPrice());
    }

    /**
     * 原子换仓结果。
     *
     * @param soldBatchId           原仓批次ID
     * @param boughtBatchId         新仓批次ID
     * @param executionBarStartTime 执行桶起点
     */
    public record RebalanceResult(
            Long soldBatchId,
            Long boughtBatchId,
            LocalDateTime executionBarStartTime) {
    }
}
