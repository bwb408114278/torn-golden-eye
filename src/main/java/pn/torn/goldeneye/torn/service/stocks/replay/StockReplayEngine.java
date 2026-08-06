package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockObservationResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchPathService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEntrySettlementService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockRejectedObservationCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.StockRoundExitGuard;
import pn.torn.goldeneye.torn.service.stocks.alert.StockRoundTransactionService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockSignalStateKey;
import pn.torn.goldeneye.torn.service.stocks.alert.StockVirtualBatchAssembler;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayEquityPoint;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayRejection;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySummary;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayTrackEnum;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayTrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 回放引擎: 按15分钟轮次逐轮重放正式组合生命周期。
 *
 * <p>对每个轮次复用正式纯领域服务在内存执行: 入场结算({@link StockEntrySettlementService}),
 * 出场结算, 持仓路径与退出评估({@link StockBatchPathService}),
 * 买入信号与资格评估({@link StockBuySignalEvaluator#evaluateSignals}),
 * 候选排序与槽位接纳; 不调用任何DAO写、通知或系统时钟。</p>
 *
 * <p>仅 {@link StockReplayTrackEnum#FORMAL_20E} 轨道同时产出无限资金影子、拒绝观察、
 * 高风险观察、原始BUY对照与动态SELL研究数据(与生产共用同一信号与排序口径);
 * {@link StockReplayTrackEnum#FORMAL_4E} 为独立历史对照,只产出正式轨道。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public class StockReplayEngine {

    /**
     * 动态SELL研究决定(公式冻结前固定)。
     */
    public static final String DYNAMIC_SHADOW_DECISION = "NOT_EVALUATED";
    /**
     * 动态SELL未评估原因(公式冻结前固定)。
     */
    public static final String DYNAMIC_SHADOW_REASON = "DYNAMIC_RULE_NOT_FROZEN";
    /**
     * 冻结的可建立理论路径拒绝原因。
     */
    private static final Set<String> PATH_REJECT_REASONS = Set.of(
            "COOLDOWN_ACTIVE", "SIGNAL_NOT_RESET", "SAME_STOCK_OPEN", "PORTFOLIO_FULL");
    /**
     * 不建立理论路径拒绝原因(数据/时效/偏离)。
     */
    private static final Set<String> NO_ENTRY_REJECT_REASONS = Set.of(
            "STYLE_MISSING", "STYLE_STALE", "DATA_NOT_CONTIGUOUS",
            "ENTRY_DATA_STALE", "ENTRY_PRICE_DEVIATION", "MATURITY_INSUFFICIENT", "DATA_NOT_READY");
    /**
     * 卖出费率(0.1%手续费)。
     */
    private static final BigDecimal SELL_FEE_RATE = StockBatchExitService.SELL_FEE_RATE;
    /**
     * 年化天数。
     */
    private static final double ANNUALIZE_DAYS = 365.25;
    /**
     * 统计精度。
     */
    private static final int STAT_SCALE = 10;

    private final StockReplayTrackEnum track;
    private final String runId;
    private final StockReplayContext context;
    private final StockReplayPortfolio portfolio;
    private final boolean collectShadow;
    private final boolean collectObservations;
    private final boolean collectDynamic;

    private final Map<String, List<StockReplayTrade>> tradesByTrack = new LinkedHashMap<>();
    private final Map<String, List<StockReplayRejection>> rejectionsByTrack = new LinkedHashMap<>();
    private final Map<String, List<StockReplayEquityPoint>> equityByTrack = new LinkedHashMap<>();
    private final List<ObservationCandidate> observations = new ArrayList<>();
    private final DynamicResearch dynamic = new DynamicResearch();

    private BigDecimal realizedReturn = BigDecimal.ZERO;
    private long messageCount = 0;
    private BigDecimal drawdownPeak = null;
    private BigDecimal maxDrawdown = BigDecimal.ZERO;
    private BigDecimal utilizationSum = BigDecimal.ZERO;
    private long utilizationCount = 0;
    private BigDecimal finalEquity = null;

    /**
     * 构造回放引擎。
     *
     * @param track   正式轨道(FORMAL_20E或FORMAL_4E)
     * @param runId   回放运行标识
     * @param context 回放上下文
     */
    public StockReplayEngine(StockReplayTrackEnum track, String runId, StockReplayContext context) {
        this.track = track;
        this.runId = runId;
        this.context = context;
        this.portfolio = new StockReplayPortfolio(track);
        this.collectShadow = track == StockReplayTrackEnum.FORMAL_20E;
        this.collectObservations = track == StockReplayTrackEnum.FORMAL_20E;
        this.collectDynamic = track == StockReplayTrackEnum.FORMAL_20E;
        tradesByTrack.put(track.getCode(), new ArrayList<>());
        equityByTrack.put(track.getCode(), new ArrayList<>());
        if (collectShadow) {
            tradesByTrack.put(StockReplayTrackEnum.UNLIMITED_SHADOW.getCode(), new ArrayList<>());
            equityByTrack.put(StockReplayTrackEnum.UNLIMITED_SHADOW.getCode(), new ArrayList<>());
        }
        if (collectObservations) {
            for (StockReplayTrackEnum observationTrack : List.of(
                    StockReplayTrackEnum.REJECTION_OBSERVATION,
                    StockReplayTrackEnum.HIGH_RISK_OBSERVATION,
                    StockReplayTrackEnum.RAW_BUY_CONTROL)) {
                rejectionsByTrack.put(observationTrack.getCode(), new ArrayList<>());
            }
        }
    }

    /**
     * 运行整个回放窗口。
     */
    public void run() {
        LocalDateTime cursor = context.request().startTime();
        LocalDateTime end = context.request().endTime();
        while (!cursor.isAfter(end)) {
            runRound(cursor);
            cursor = cursor.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        }
        finish();
    }

    /**
     * 运行单个轮次。
     *
     * @param t 轮次时间
     */
    void runRound(LocalDateTime t) {
        Map<Integer, TornStockMarketBar15mDO> barByStock = context.barsAt(t);
        Map<Integer, TornStockStrategyFeature15mDO> featureByStock = context.featuresAt(t);
        Map<Integer, TornStockMonthlyStateDO> monthlyByStock = context.monthlyStatesFor(t);
        RoundSnapshot snapshot = buildSnapshot(t, barByStock, featureByStock, monthlyByStock);

        StockEntrySettlementService.EntrySettlementResult entryResult =
                context.entrySettlementService().processEntryPending(snapshot, barByStock, t, t);
        for (TornStockVirtualBatchDO batch : entryResult.filledBatches()) {
            recordBuyTrade(batch, t);
        }

        List<TornStockVirtualBatchDO> exitFilled =
                context.entrySettlementService().processExitPending(snapshot, barByStock, t);
        List<TornStockVirtualBatchDO> formalExitFilled = exitFilled.stream()
                .filter(batch -> StockLedgerTypeEnum.FORMAL.getCode().equals(batch.getLedgerType()))
                .toList();
        for (TornStockVirtualBatchDO batch : exitFilled) {
            recordSellTrade(batch, t);
        }

        context.pathService().updatePathsAndEvaluateExits(snapshot, barByStock, featureByStock, t);

        if (collectDynamic) {
            collectDynamicResearch(portfolio.activeBatches(), barByStock, featureByStock, t);
        }

        StockBuySignalEvaluator.BuySignalResult buyResult = context.buyEvaluator()
                .evaluateSignals(snapshot, barByStock, monthlyByStock, portfolio.signalStates(), t);
        if (!buyResult.allEvaluations().isEmpty()) {
            List<CandidateInfo> ranked = context.rankingPolicy().rank(buyResult.formalCandidates());
            List<CandidateInfo> candidates =
                    StockRoundExitGuard.excludeFormalExitStocks(ranked, formalExitFilled);
            Map<Integer, StockBuySignalEvaluator.SignalEvaluation> evalByStock =
                    buyResult.allEvaluations().stream()
                            .collect(Collectors.toMap(StockBuySignalEvaluator.SignalEvaluation::stocksId,
                                    Function.identity(), (left, right) -> left));
            allocate(candidates, barByStock, monthlyByStock, t, evalByStock);
            if (collectObservations) {
                collectObservationFeeds(buyResult.allEvaluations(), t);
            }
            updateSignalStates(buyResult.allEvaluations(), t);
        }
        updateCloseStates(formalExitFilled);

        recordEquityPoint(t, barByStock);
    }

    private RoundSnapshot buildSnapshot(
            LocalDateTime t,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
            Map<Integer, TornStockMonthlyStateDO> monthlyByStock) {
        List<TornStockVirtualBatchDO> shadowBatches = portfolio.activeBatches().stream()
                .filter(batch -> StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(batch.getLedgerType()))
                .toList();
        return new RoundSnapshot(
                new ArrayList<>(barByStock.values()),
                new ArrayList<>(featureByStock.values()),
                new ArrayList<>(monthlyByStock.values()),
                portfolio.activeBatches(),
                shadowBatches,
                new ArrayList<>(portfolio.signalStates().values()),
                portfolio.slots(),
                t);
    }

    // ==================== 正式候选接纳 ====================

    private void allocate(List<CandidateInfo> candidates,
                          Map<Integer, TornStockMarketBar15mDO> barByStock,
                          Map<Integer, TornStockMonthlyStateDO> monthlyByStock,
                          LocalDateTime t,
                          Map<Integer, StockBuySignalEvaluator.SignalEvaluation> evalByStock) {
        int candidateRank = 0;
        for (CandidateInfo candidate : candidates) {
            candidateRank++;
            TornStockPortfolioSlotDO slot = portfolio.firstAvailableSlot();
            StockBuySignalEvaluator.SignalEvaluation evaluation = evalByStock.get(candidate.stocksId());
            TornStockMarketBar15mDO bar = barByStock.get(candidate.stocksId());
            if (slot == null) {
                handleCapacityReject(candidate, evaluation, "PORTFOLIO_FULL", candidateRank, t, monthlyByStock, bar);
                continue;
            }
            if (bar == null || bar.getLastPrice() == null || bar.getLastPrice().signum() <= 0) {
                handleCapacityReject(candidate, evaluation, "DATA_NOT_READY", candidateRank, t, monthlyByStock, bar);
                continue;
            }
            BigDecimal signalPrice = bar.getLastPrice();
            BigDecimal reservedAmount = slot.getAvailableCash();
            Long quantity = StockPortfolioService.calculateQuantity(reservedAmount, signalPrice);
            if (quantity == null || quantity <= 0) {
                handleCapacityReject(candidate, evaluation, "INSUFFICIENT_FUNDS", candidateRank, t, monthlyByStock, bar);
                continue;
            }
            acceptFormalCandidate(candidate, slot, monthlyByStock.get(candidate.stocksId()),
                    signalPrice, quantity, t);
        }
    }

    private void acceptFormalCandidate(CandidateInfo candidate, TornStockPortfolioSlotDO slot,
                                       TornStockMonthlyStateDO monthlyState,
                                       BigDecimal signalPrice, Long quantity, LocalDateTime t) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo("F" + t.format(FORMATTER) + candidate.stocksId());
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setStocksId(candidate.stocksId());
        batch.setStocksShortname(candidate.stocksShortname());
        batch.setPrimaryStrategy(candidate.primaryStrategy().getCode());
        batch.setQualityScore(candidate.qualityScore());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSlotId(slot.getId());
        batch.setSlotNo(slot.getSlotNo());
        batch.setSignalTime(t);
        batch.setQuantity(quantity);
        StockVirtualBatchAssembler.applySignalFields(batch,
                StockVirtualBatchAssembler.buildSignalFields(signalPrice, t, monthlyState));
        portfolio.addBatch(batch);
        context.portfolioService().reserveSlot(slot, slot.getAvailableCash(), batch.getId());
    }

    private void handleCapacityReject(CandidateInfo candidate,
                                      StockBuySignalEvaluator.SignalEvaluation evaluation,
                                      String rejectReason, int candidateRank, LocalDateTime t,
                                      Map<Integer, TornStockMonthlyStateDO> monthlyByStock,
                                      TornStockMarketBar15mDO bar) {
        if (collectShadow && evaluation != null && evaluation.edgeTriggered() && bar != null
                && bar.getLastPrice() != null && bar.getLastPrice().signum() > 0) {
            createShadowBatch(evaluation, bar.getLastPrice(), t);
        }
        if (collectObservations && evaluation != null) {
            String mappedReason = "PORTFOLIO_FULL".equals(rejectReason)
                    || "INSUFFICIENT_FUNDS".equals(rejectReason)
                    ? "PORTFOLIO_FULL" : rejectReason;
            observations.add(ObservationCandidate.ofRejection(
                    evaluation, mappedReason, candidateRank, t));
        }
    }

    private void createShadowBatch(StockBuySignalEvaluator.SignalEvaluation evaluation,
                                   BigDecimal signalPrice, LocalDateTime t) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo("S" + t.format(FORMATTER) + evaluation.stocksId());
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        batch.setStocksId(evaluation.stocksId());
        batch.setStocksShortname(evaluation.stocksShortname());
        batch.setPrimaryStrategy(evaluation.primaryStrategy().getStrategyType().getCode());
        batch.setQualityScore(evaluation.qualityScore());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSignalTime(t);
        StockVirtualBatchAssembler.applySignalFields(batch,
                StockVirtualBatchAssembler.buildSignalFields(signalPrice, t, evaluation.monthlyState()));
        portfolio.addBatch(batch);
    }

    // ==================== 观察馈送 ====================

    private void collectObservationFeeds(
            List<StockBuySignalEvaluator.SignalEvaluation> allEvaluations, LocalDateTime t) {
        for (StockBuySignalEvaluator.SignalEvaluation evaluation : allEvaluations) {
            if (!evaluation.edgeTriggered() || evaluation.primaryStrategy() == null) {
                continue;
            }
            observations.add(ObservationCandidate.ofRawBuy(evaluation, t));
            if (evaluation.context() != null
                    && StockRiskLevelEnum.HIGH == evaluation.context().riskLevel()) {
                observations.add(ObservationCandidate.ofHighRisk(evaluation, t));
            }
            if (evaluation.eligibilityResult() != null
                    && StockEligibilityResultEnum.REJECTED == evaluation.eligibilityResult().result()) {
                observations.add(ObservationCandidate.ofRejection(
                        evaluation, mapRejectionReason(evaluation), null, t));
            }
        }
    }

    /**
     * 将生产资格拒绝原因映射为冻结拒绝观察分组编码。
     *
     * @param evaluation 信号评估
     * @return 冻结拒绝原因编码
     */
    static String mapRejectionReason(StockBuySignalEvaluator.SignalEvaluation evaluation) {
        if (evaluation == null || evaluation.eligibilityResult() == null
                || evaluation.eligibilityResult().reasons().isEmpty()) {
            return "UNKNOWN";
        }
        String reason = evaluation.eligibilityResult().reasons().getFirst();
        return switch (reason) {
            case "RESET_NOT_OBSERVED" -> "SIGNAL_NOT_RESET";
            case "SAME_STOCK_ACTIVE" -> "SAME_STOCK_OPEN";
            case "NO_AVAILABLE_SLOT", "INSUFFICIENT_FUNDS" -> "PORTFOLIO_FULL";
            default -> reason;
        };
    }

    // ==================== 信号状态(内存镜像正式状态机) ====================

    private void updateSignalStates(List<StockBuySignalEvaluator.SignalEvaluation> allEvaluations,
                                    LocalDateTime roundTime) {
        for (StockBuySignalEvaluator.SignalEvaluation evaluation : allEvaluations) {
            if (evaluation.stocksId() == null || evaluation.evaluatedStrategies() == null) {
                continue;
            }
            for (StockBuyStrategy strategy : evaluation.evaluatedStrategies()) {
                if (strategy == null || strategy.getStrategyType() == null) {
                    continue;
                }
                StockSignalStateKey key = new StockSignalStateKey(
                        evaluation.stocksId(), strategy.getStrategyType().getCode(),
                        StockRoundTransactionService.BUY_RULE_VERSION);
                TornStockSignalStateDO state = portfolio.signalStates().get(key);
                if (state == null) {
                    state = new TornStockSignalStateDO();
                    portfolio.signalStates().put(key, state);
                }
                boolean previousActive = Boolean.TRUE.equals(state.getConditionActive());
                boolean currentActive = evaluation.matchedStrategies() != null
                        && evaluation.matchedStrategies().contains(strategy);
                state.setStocksId(evaluation.stocksId());
                state.setStrategyType(strategy.getStrategyType().getCode());
                state.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
                state.setConditionActive(currentActive);
                state.setLastEvaluatedRoundTime(roundTime);
                if (currentActive && !previousActive) {
                    state.setLastSignalTime(roundTime);
                }
                if (!currentActive && previousActive) {
                    state.setResetObserved(true);
                }
                if (state.getResetObserved() == null) {
                    state.setResetObserved(false);
                }
            }
        }
    }

    private void updateCloseStates(List<TornStockVirtualBatchDO> formalExitFilled) {
        for (TornStockVirtualBatchDO batch : formalExitFilled) {
            if (batch == null || batch.getStocksId() == null || batch.getPrimaryStrategy() == null
                    || batch.getBuyRuleVersion() == null) {
                continue;
            }
            StockSignalStateKey key = new StockSignalStateKey(
                    batch.getStocksId(), batch.getPrimaryStrategy(), batch.getBuyRuleVersion());
            TornStockSignalStateDO state = portfolio.signalStates().get(key);
            if (state == null) {
                state = new TornStockSignalStateDO();
                portfolio.signalStates().put(key, state);
            }
            state.setCooldownUntil(batch.getCooldownUntil());
            state.setLastCloseType(resolveLastCloseType(batch));
            state.setResetObserved(false);
        }
    }

    private static String resolveLastCloseType(TornStockVirtualBatchDO batch) {
        if (batch.getBatchStatus() != null && !batch.getBatchStatus().isBlank()) {
            return batch.getBatchStatus();
        }
        return batch.getExitReason();
    }

    // ==================== 动态SELL研究数据采集 ====================

    private void collectDynamicResearch(List<TornStockVirtualBatchDO> activeBatches,
                                        Map<Integer, TornStockMarketBar15mDO> barByStock,
                                        Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
                                        LocalDateTime t) {
        for (TornStockVirtualBatchDO batch : activeBatches) {
            if (!StockLedgerTypeEnum.FORMAL.getCode().equals(batch.getLedgerType())
                    || !StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus())) {
                continue;
            }
            TornStockMarketBar15mDO bar = barByStock.get(batch.getStocksId());
            if (!Stock15mBarBuildService.isUsable(bar)) {
                continue;
            }
            TornStockStrategyFeature15mDO feature = featureByStock.get(batch.getStocksId());
            BigDecimal holdHours = batch.getEntryTime() == null ? null
                    : BigDecimal.valueOf(Duration.between(batch.getEntryTime(), t).toMinutes() / 60.0);
            List<BigDecimal> inputs = new ArrayList<>();
            inputs.add(batch.getCurrentNetReturn());
            inputs.add(batch.getMfe());
            inputs.add(batch.getMae());
            inputs.add(batch.getPeakDrawdown());
            inputs.add(feature == null ? null : feature.getZscore1d());
            inputs.add(feature == null ? null : feature.getReturn6h());
            inputs.add(feature == null ? null : feature.getReturn1d());
            inputs.add(feature == null ? null : feature.getMa7d());
            inputs.add(feature == null ? null : feature.getMa30d());
            inputs.add(feature == null ? null : feature.getPosition30());
            inputs.add(feature == null ? null : feature.getWidth30d());
            inputs.add(holdHours);
            dynamic.observations++;
            for (BigDecimal value : inputs) {
                if (value != null) {
                    dynamic.present++;
                } else {
                    dynamic.missing++;
                }
            }
            dynamic.pathByFamily.merge(
                    batch.getPrimaryStrategy() == null ? "UNKNOWN" : batch.getPrimaryStrategy(),
                    1, Integer::sum);
        }
    }

    // ==================== 交易与净值记录 ====================

    private void recordBuyTrade(TornStockVirtualBatchDO batch, LocalDateTime t) {
        String ledgerType = batch.getLedgerType();
        String trackCode;
        if (StockLedgerTypeEnum.FORMAL.getCode().equals(ledgerType)) {
            trackCode = track.getCode();
            messageCount++;
        } else if (collectShadow
                && StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(ledgerType)) {
            trackCode = StockReplayTrackEnum.UNLIMITED_SHADOW.getCode();
        } else {
            return;
        }
        tradesByTrack.get(trackCode).add(new StockReplayTrade(
                runId, trackCode, t, batch.getStocksId(), batch.getStocksShortname(),
                "BUY", batch.getPrimaryStrategy(), batch.getSignalTime(), batch.getEntryTime(), null,
                batch.getQuantity(), batch.getEntryReferencePrice(), null,
                batch.getInvestedCash(), null, null, null, null, batch.getBatchNo(), null));
    }

    private void recordSellTrade(TornStockVirtualBatchDO batch, LocalDateTime t) {
        String ledgerType = batch.getLedgerType();
        String trackCode;
        if (StockLedgerTypeEnum.FORMAL.getCode().equals(ledgerType)) {
            trackCode = track.getCode();
            messageCount++;
            if (batch.getSellProceeds() != null && batch.getInvestedCash() != null) {
                realizedReturn = realizedReturn.add(batch.getSellProceeds())
                        .subtract(batch.getInvestedCash());
            }
        } else if (collectShadow
                && StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(ledgerType)) {
            trackCode = StockReplayTrackEnum.UNLIMITED_SHADOW.getCode();
        } else {
            return;
        }
        BigDecimal holdHours = batch.getEntryTime() == null || batch.getExitTime() == null ? null
                : BigDecimal.valueOf(Duration.between(batch.getEntryTime(), batch.getExitTime())
                        .toMinutes() / 60.0);
        tradesByTrack.get(trackCode).add(new StockReplayTrade(
                runId, trackCode, t, batch.getStocksId(), batch.getStocksShortname(),
                "SELL", batch.getPrimaryStrategy(), batch.getSignalTime(), batch.getEntryTime(),
                batch.getExitTime(), batch.getQuantity(), batch.getEntryReferencePrice(),
                batch.getExitReferencePrice(), batch.getInvestedCash(), batch.getSellProceeds(),
                batch.getNetReturn(), batch.getBatchStatus(), batch.getExitReason(),
                batch.getBatchNo(), holdHours));
    }

    private void recordEquityPoint(LocalDateTime t,
                                   Map<Integer, TornStockMarketBar15mDO> barByStock) {
        BigDecimal cashAndReserved =
                context.portfolioService().calculateCashAndReserved(portfolio.slots());
        int occupiedSlots = (int) portfolio.slots().stream()
                .filter(slot -> !StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .count();
        BigDecimal utilization = track.getSlotCount() <= 0 ? null
                : BigDecimal.valueOf(occupiedSlots)
                        .divide(BigDecimal.valueOf(track.getSlotCount()), STAT_SCALE, RoundingMode.HALF_UP);
        if (utilization != null) {
            utilizationSum = utilizationSum.add(utilization);
            utilizationCount++;
        }

        BigDecimal marketValue = BigDecimal.ZERO;
        boolean missingPrice = false;
        int openPositions = 0;
        for (TornStockVirtualBatchDO batch : portfolio.activeBatches()) {
            if (!isOpenStatus(batch.getBatchStatus())) {
                continue;
            }
            TornStockMarketBar15mDO bar = barByStock.get(batch.getStocksId());
            if (bar == null || !Stock15mBarBuildService.isUsable(bar) || bar.getLastPrice() == null) {
                missingPrice = true;
                continue;
            }
            openPositions++;
            marketValue = marketValue.add(
                    BigDecimal.valueOf(batch.getQuantity() == null ? 0 : batch.getQuantity())
                            .multiply(bar.getLastPrice())
                            .multiply(SELL_FEE_RATE));
        }
        BigDecimal equity = missingPrice ? null : cashAndReserved.add(marketValue);
        equityByTrack.get(track.getCode()).add(new StockReplayEquityPoint(
                runId, track.getCode(), t, equity, cashAndReserved, openPositions, realizedReturn, utilization));
        if (equity != null) {
            finalEquity = equity;
            if (drawdownPeak == null || equity.compareTo(drawdownPeak) > 0) {
                drawdownPeak = equity;
            }
            if (drawdownPeak.signum() > 0) {
                BigDecimal drawdown = equity.divide(drawdownPeak, STAT_SCALE, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE);
                if (drawdown.compareTo(maxDrawdown) < 0) {
                    maxDrawdown = drawdown;
                }
            }
        }
        if (collectShadow) {
            recordShadowEquityPoint(t, barByStock);
        }
    }

    private void recordShadowEquityPoint(LocalDateTime t,
                                         Map<Integer, TornStockMarketBar15mDO> barByStock) {
        BigDecimal marketValue = BigDecimal.ZERO;
        int openPositions = 0;
        boolean missingPrice = false;
        for (TornStockVirtualBatchDO batch : portfolio.activeBatches()) {
            if (!StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(batch.getLedgerType())
                    || !isOpenStatus(batch.getBatchStatus())) {
                continue;
            }
            TornStockMarketBar15mDO bar = barByStock.get(batch.getStocksId());
            if (bar == null || !Stock15mBarBuildService.isUsable(bar) || bar.getLastPrice() == null) {
                missingPrice = true;
                continue;
            }
            openPositions++;
            marketValue = marketValue.add(bar.getLastPrice().multiply(SELL_FEE_RATE));
        }
        equityByTrack.get(StockReplayTrackEnum.UNLIMITED_SHADOW.getCode()).add(new StockReplayEquityPoint(
                runId, StockReplayTrackEnum.UNLIMITED_SHADOW.getCode(), t,
                missingPrice ? null : marketValue, BigDecimal.ZERO, openPositions,
                null, null));
    }

    private static boolean isOpenStatus(String status) {
        return StockBatchStatusEnum.OPEN.getCode().equals(status)
                || StockBatchStatusEnum.DATA_STALE.getCode().equals(status)
                || StockBatchStatusEnum.EXIT_PENDING.getCode().equals(status)
                || StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(status);
    }

    // ==================== 收尾: 理论观察与摘要 ====================

    private void finish() {
        for (ObservationCandidate observation : observations) {
            emitRejection(observation);
        }
    }

    private void emitRejection(ObservationCandidate observation) {
        StockReplayRejection row;
        if (StockReplayTrackEnum.REJECTION_OBSERVATION.getCode().equals(observation.track())
                && NO_ENTRY_REJECT_REASONS.contains(observation.rejectReason())) {
            row = new StockReplayRejection(
                    runId, observation.track(), observation.roundTime(),
                    observation.stocksId(), observation.stocksShortname(),
                    observation.strategyType(), observation.qualityScore(),
                    observation.monthlyStyle(), observation.riskLevel(),
                    observation.eligibilityResult(), observation.eligibilityReasons(),
                    observation.candidateRank(), observation.portfolioDecision(),
                    observation.rejectReason(),
                    StockObservationResultEnum.NO_THEORETICAL_ENTRY.getCode(),
                    null, null, null, null, null, null, null, null, null);
        } else {
            StockRejectedObservationCalculator.Result result = calculateObservation(observation);
            row = toRejectionRow(observation, result);
        }
        rejectionsByTrack.get(observation.track()).add(row);
    }

    private StockReplayRejection toRejectionRow(ObservationCandidate observation,
                                                StockRejectedObservationCalculator.Result result) {
        return new StockReplayRejection(
                runId, observation.track(), observation.roundTime(),
                observation.stocksId(), observation.stocksShortname(),
                observation.strategyType(), observation.qualityScore(),
                observation.monthlyStyle(), observation.riskLevel(),
                observation.eligibilityResult(), observation.eligibilityReasons(),
                observation.candidateRank(), observation.portfolioDecision(),
                observation.rejectReason(),
                result == null ? null : result.resultCode(),
                result == null ? null : result.laterMfe(),
                result == null ? null : result.laterMae(),
                result == null ? null : result.theoreticalEntryTime(),
                result == null ? null : result.theoreticalEntryPrice(),
                result == null ? null : result.theoreticalExitSignalTime(),
                result == null ? null : result.theoreticalExitTime(),
                result == null ? null : result.theoreticalExitPrice(),
                result == null ? null : result.theoreticalCloseType(),
                result == null ? null : result.theoreticalNetReturn());
    }

    private StockRejectedObservationCalculator.Result calculateObservation(
            ObservationCandidate observation) {
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setSignalReferencePrice(observation.signalReferencePrice());
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo("R" + observation.roundTime().format(FORMATTER) + observation.stocksId());
        batch.setExpectedEntryBarTime(observation.expectedEntryBarTime());
        batch.setEntryStaleAt(observation.entryStaleAt());
        batch.setPrimaryStrategy(observation.strategyType());
        List<TornStockMarketBar15mDO> bars = new ArrayList<>(
                context.windowData().barsByStock()
                        .getOrDefault(observation.stocksId(), new TreeMap<>()).values());
        List<TornStockStrategyFeature15mDO> features = new ArrayList<>(
                context.windowData().featuresByStock()
                        .getOrDefault(observation.stocksId(), new TreeMap<>()).values());
        return StockRejectedObservationCalculator.calculate(event, batch, bars, features);
    }

    // ==================== 输出访问 ====================

    /**
     * 按轨道编码分组的交易记录。
     *
     * @return 轨道编码 → 交易记录
     */
    public Map<String, List<StockReplayTrade>> tradesByTrack() {
        return tradesByTrack;
    }

    /**
     * 按轨道编码分组的拒绝/观察记录。
     *
     * @return 轨道编码 → 拒绝/观察记录
     */
    public Map<String, List<StockReplayRejection>> rejectionsByTrack() {
        return rejectionsByTrack;
    }

    /**
     * 按轨道编码分组的净值点。
     *
     * @return 轨道编码 → 净值点
     */
    public Map<String, List<StockReplayEquityPoint>> equityByTrack() {
        return equityByTrack;
    }

    /**
     * 构建本引擎产出各轨道的摘要。
     *
     * @param windowDays 回放窗口自然日数
     * @return 轨道编码 → 轨道摘要
     */
    public Map<String, StockReplaySummary.TrackSummary> buildSummaries(long windowDays) {
        Map<String, StockReplaySummary.TrackSummary> summaries = new LinkedHashMap<>();
        for (Map.Entry<String, List<StockReplayTrade>> entry : tradesByTrack.entrySet()) {
            summaries.put(entry.getKey(), buildTradeTrackSummary(entry.getKey(), entry.getValue(), windowDays));
        }
        for (Map.Entry<String, List<StockReplayRejection>> entry : rejectionsByTrack.entrySet()) {
            summaries.put(entry.getKey(), buildRejectionTrackSummary(entry.getKey(), entry.getValue()));
        }
        return summaries;
    }

    private StockReplaySummary.TrackSummary buildTradeTrackSummary(String trackCode,
                                                                   List<StockReplayTrade> trades,
                                                                   long windowDays) {
        long buys = trades.stream().filter(t -> "BUY".equals(t.side())).count();
        long sells = trades.stream().filter(t -> "SELL".equals(t.side())).count();
        List<StockReplayEquityPoint> points = equityByTrack.getOrDefault(trackCode, List.of());
        List<BigDecimal> equityValues = points.stream()
                .map(StockReplayEquityPoint::equity)
                .filter(java.util.Objects::nonNull)
                .toList();
        BigDecimal initialCash = StockReplayTrackEnum.valueOf(trackCode).isFormal()
                ? StockReplayTrackEnum.valueOf(trackCode).getInitialCashPerSlot()
                .multiply(BigDecimal.valueOf(StockReplayTrackEnum.valueOf(trackCode).getSlotCount()))
                : null;
        BigDecimal intervalReturn = null;
        BigDecimal annualized = null;
        BigDecimal finalEquityValue = null;
        if (initialCash != null && !equityValues.isEmpty()) {
            finalEquityValue = equityValues.getLast();
            intervalReturn = finalEquityValue.divide(initialCash, STAT_SCALE, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            annualized = annualize(intervalReturn, windowDays);
        }
        BigDecimal drawdown = maxDrawdownForTrack(trackCode);
        BigDecimal utilization = utilizationCount == 0 ? null
                : utilizationSum.divide(BigDecimal.valueOf(utilizationCount), STAT_SCALE, RoundingMode.HALF_UP);
        BigDecimal medianHoldHours = medianHoldHours(trades);
        long totalMessages = StockReplayTrackEnum.valueOf(trackCode).isFormal() ? messageCount : 0;
        BigDecimal messagesPerDay = windowDays <= 0 ? null
                : BigDecimal.valueOf(totalMessages)
                        .divide(BigDecimal.valueOf(windowDays), STAT_SCALE, RoundingMode.HALF_UP);
        return new StockReplaySummary.TrackSummary(
                trackCode, StockReplayTrackEnum.valueOf(trackCode).getDisplayName(),
                StockReplayTrackEnum.valueOf(trackCode).getSlotCount(),
                StockReplayTrackEnum.valueOf(trackCode).getInitialCashPerSlot(),
                trades.size(), buys, sells,
                intervalReturn, annualized, drawdown, utilization, medianHoldHours,
                totalMessages, messagesPerDay, 0, StockReplaySummary.emptyReasonMap(),
                0, StockReplaySummary.emptyReasonMap(),
                finalEquityValue, points.size(), null);
    }

    private StockReplaySummary.TrackSummary buildRejectionTrackSummary(String trackCode,
                                                                       List<StockReplayRejection> rejections) {
        TreeMap<String, Integer> reasons = StockReplaySummary.emptyReasonMap();
        TreeMap<String, Integer> results = StockReplaySummary.emptyReasonMap();
        long observed = 0;
        for (StockReplayRejection rejection : rejections) {
            StockReplaySummary.mergeReason(reasons, rejection.rejectReason());
            StockReplaySummary.mergeReason(results, rejection.observationResult());
            if ("OBSERVATION_COMPLETED".equals(rejection.observationResult())) {
                observed++;
            }
        }
        return new StockReplaySummary.TrackSummary(
                trackCode, StockReplayTrackEnum.valueOf(trackCode).getDisplayName(),
                0, null, 0, 0, 0,
                null, null, null, null, null, 0, null,
                rejections.size(), reasons, observed, results,
                null, 0, null);
    }

    /**
     * 动态SELL研究数据摘要。
     *
     * @return 动态SELL研究摘要
     */
    public StockReplaySummary.DynamicSellSummary dynamicSellSummary() {
        long total = dynamic.present + dynamic.missing;
        BigDecimal coverage = total == 0 ? null
                : BigDecimal.valueOf(dynamic.present)
                        .divide(BigDecimal.valueOf(total), STAT_SCALE, RoundingMode.HALF_UP);
        BigDecimal missingRate = total == 0 ? null
                : BigDecimal.valueOf(dynamic.missing)
                        .divide(BigDecimal.valueOf(total), STAT_SCALE, RoundingMode.HALF_UP);
        return new StockReplaySummary.DynamicSellSummary(
                DYNAMIC_SHADOW_DECISION, DYNAMIC_SHADOW_REASON, dynamic.observations,
                coverage, missingRate, new TreeMap<>(dynamic.pathByFamily), 0, 0, 0);
    }

    private static BigDecimal annualize(BigDecimal intervalReturn, long windowDays) {
        if (intervalReturn == null || windowDays <= 0) {
            return null;
        }
        double base = intervalReturn.add(BigDecimal.ONE).doubleValue();
        if (base <= 0) {
            return null;
        }
        double power = ANNUALIZE_DAYS / windowDays;
        double result = Math.pow(base, power) - 1;
        if (Double.isFinite(result)) {
            return BigDecimal.valueOf(result).setScale(STAT_SCALE, RoundingMode.HALF_UP);
        }
        return null;
    }

    private BigDecimal maxDrawdownForTrack(String trackCode) {
        return trackCode.equals(track.getCode()) ? maxDrawdown : BigDecimal.ZERO;
    }

    private static BigDecimal medianHoldHours(List<StockReplayTrade> trades) {
        List<BigDecimal> hours = trades.stream()
                .filter(t -> "SELL".equals(t.side()) && t.holdHours() != null)
                .map(StockReplayTrade::holdHours)
                .sorted()
                .toList();
        if (hours.isEmpty()) {
            return null;
        }
        int mid = hours.size() / 2;
        return hours.size() % 2 == 0
                ? hours.get(mid - 1).add(hours.get(mid)).divide(BigDecimal.valueOf(2), STAT_SCALE, RoundingMode.HALF_UP)
                : hours.get(mid);
    }

    private static final java.time.format.DateTimeFormatter FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /**
     * 待结算理论观察候选(收尾阶段使用全窗口数据计算前向路径)。
     *
     * @param track                 观察轨道
     * @param roundTime             信号轮次时间
     * @param stocksId              股票ID
     * @param stocksShortname       股票简称
     * @param strategyType          主策略编码
     * @param qualityScore          质量分
     * @param monthlyStyle          冻结风格
     * @param riskLevel             冻结风险
     * @param eligibilityResult     资格结果编码
     * @param eligibilityReasons    资格原因
     * @param candidateRank         候选排名
     * @param portfolioDecision     组合决策
     * @param rejectReason          拒绝/观察原因编码
     * @param signalReferencePrice  信号参考价
     * @param expectedEntryBarTime  期望入场bar时间
     * @param entryStaleAt          入场过期时间
     * @author Bai
     * @version 1.2.14
     * @since 2026.08.06
     */
    record ObservationCandidate(
            String track,
            LocalDateTime roundTime,
            Integer stocksId,
            String stocksShortname,
            String strategyType,
            BigDecimal qualityScore,
            String monthlyStyle,
            String riskLevel,
            String eligibilityResult,
            String eligibilityReasons,
            Integer candidateRank,
            String portfolioDecision,
            String rejectReason,
            BigDecimal signalReferencePrice,
            LocalDateTime expectedEntryBarTime,
            LocalDateTime entryStaleAt
    ) {

        static ObservationCandidate ofRejection(
                StockBuySignalEvaluator.SignalEvaluation evaluation, String rejectReason,
                Integer candidateRank, LocalDateTime t) {
            return new ObservationCandidate(
                    StockReplayTrackEnum.REJECTION_OBSERVATION.getCode(),
                    t, evaluation.stocksId(), evaluation.stocksShortname(),
                    evaluation.primaryStrategy().getStrategyType().getCode(),
                    evaluation.qualityScore(),
                    monthlyStyle(evaluation), riskLevel(evaluation),
                    resultCode(evaluation), reasons(evaluation), candidateRank,
                    "REJECTED",
                    rejectReason,
                    evaluation.context() == null ? null : evaluation.context().referencePrice(),
                    t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES),
                    t.plusMinutes(35));
        }

        static ObservationCandidate ofRawBuy(
                StockBuySignalEvaluator.SignalEvaluation evaluation, LocalDateTime t) {
            return new ObservationCandidate(
                    StockReplayTrackEnum.RAW_BUY_CONTROL.getCode(),
                    t, evaluation.stocksId(), evaluation.stocksShortname(),
                    evaluation.primaryStrategy().getStrategyType().getCode(),
                    evaluation.qualityScore(),
                    monthlyStyle(evaluation), riskLevel(evaluation),
                    resultCode(evaluation), reasons(evaluation), null,
                    "OBSERVED", "RAW_BUY_SIGNAL",
                    evaluation.context() == null ? null : evaluation.context().referencePrice(),
                    t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES),
                    t.plusMinutes(35));
        }

        static ObservationCandidate ofHighRisk(
                StockBuySignalEvaluator.SignalEvaluation evaluation, LocalDateTime t) {
            return new ObservationCandidate(
                    StockReplayTrackEnum.HIGH_RISK_OBSERVATION.getCode(),
                    t, evaluation.stocksId(), evaluation.stocksShortname(),
                    evaluation.primaryStrategy().getStrategyType().getCode(),
                    evaluation.qualityScore(),
                    monthlyStyle(evaluation), riskLevel(evaluation),
                    resultCode(evaluation), reasons(evaluation), null,
                    "OBSERVED", "HIGH_RISK_OBSERVATION",
                    evaluation.context() == null ? null : evaluation.context().referencePrice(),
                    t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES),
                    t.plusMinutes(35));
        }

        private static String monthlyStyle(StockBuySignalEvaluator.SignalEvaluation evaluation) {
            return evaluation.monthlyState() == null ? null : evaluation.monthlyState().getStrategyFitPrior();
        }

        private static String riskLevel(StockBuySignalEvaluator.SignalEvaluation evaluation) {
            return evaluation.context() == null || evaluation.context().riskLevel() == null
                    ? null : evaluation.context().riskLevel().getCode();
        }

        private static String resultCode(StockBuySignalEvaluator.SignalEvaluation evaluation) {
            return evaluation.eligibilityResult() == null ? null
                    : evaluation.eligibilityResult().result().getCode();
        }

        private static String reasons(StockBuySignalEvaluator.SignalEvaluation evaluation) {
            if (evaluation.eligibilityResult() == null
                    || evaluation.eligibilityResult().reasons().isEmpty()) {
                return null;
            }
            return String.join("|", evaluation.eligibilityResult().reasons());
        }
    }

    /**
     * 动态SELL研究输入计数器(覆盖率/缺失率/路径分布)。
     */
    private static final class DynamicResearch {
        private long observations;
        private long present;
        private long missing;
        private final TreeMap<String, Integer> pathByFamily = new TreeMap<>();
    }
}
