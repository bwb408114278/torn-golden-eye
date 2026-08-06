package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.*;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.replay.model.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 回放引擎: 按15分钟轮次逐轮重放正式组合生命周期。
 *
 * <p>对每个轮次复用正式纯领域服务在内存执行: 入场结算({@link StockEntrySettlementService}),
 * 出场结算, 持仓路径与退出评估({@code StockBatchPathService}),
 * 买入信号与资格评估({@link StockBuySignalEvaluator#evaluateSignals}),
 * 候选排序与槽位接纳; 不调用任何DAO写、通知或系统时钟。</p>
 *
 * <p>仅 {@link StockReplayTrackEnum#FORMAL_20E} 轨道同时产出无限资金影子、拒绝观察、
 * 高风险观察、原始BUY对照与动态SELL研究数据(与生产共用同一信号与排序口径);
 * {@link StockReplayTrackEnum#FORMAL_4E} 为独立历史对照,只产出正式轨道。</p>
 *
 * <p>职责拆分: 信号状态镜像({@link StockReplaySignalStateMirror})、指标与净值
 * ({@link StockReplayMetrics})、摘要构建({@link StockReplaySummaryBuilder})、
 * 动态SELL研究采集({@link StockReplayDynamicResearch})、观察候选
 * ({@link StockReplayObservationCandidate})分别独立成类。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public class StockReplayEngine {

    /**
     * 组合满仓拒绝原因。
     */
    private static final String REJECT_PORTFOLIO_FULL = "PORTFOLIO_FULL";
    /**
     * 资金不足拒绝原因。
     */
    private static final String REJECT_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    /**
     * 不建立理论路径拒绝原因(数据/时效/偏离)。
     */
    private static final Set<String> NO_ENTRY_REJECT_REASONS = Set.of(
            "STYLE_MISSING", "STYLE_STALE", "DATA_NOT_CONTIGUOUS",
            "ENTRY_DATA_STALE", "ENTRY_PRICE_DEVIATION", "MATURITY_INSUFFICIENT", "DATA_NOT_READY");

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
    private final List<StockReplayObservationCandidate> observations = new ArrayList<>();

    private final StockReplayMetrics metrics;
    private final StockReplayDynamicResearch dynamicResearch;
    private final StockReplaySignalStateMirror signalStateMirror;
    private final StockReplaySummaryBuilder summaryBuilder;

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
        this.metrics = new StockReplayMetrics(context, portfolio, track, runId, equityByTrack, collectShadow);
        this.dynamicResearch = new StockReplayDynamicResearch();
        this.signalStateMirror = new StockReplaySignalStateMirror(portfolio);
        this.summaryBuilder = new StockReplaySummaryBuilder(
                tradesByTrack, rejectionsByTrack, equityByTrack, track, metrics);
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
            dynamicResearch.collect(portfolio.activeBatches(), barByStock, featureByStock, t);
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
            signalStateMirror.updateFromEvaluations(buyResult.allEvaluations(), t);
        }
        signalStateMirror.updateFromFormalExits(formalExitFilled);

        metrics.recordEquityPoint(t, barByStock);
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
            tryAcceptCandidate(candidate, candidateRank, barByStock, monthlyByStock,
                    evalByStock.get(candidate.stocksId()), t);
        }
    }

    private void tryAcceptCandidate(CandidateInfo candidate, int candidateRank,
                                    Map<Integer, TornStockMarketBar15mDO> barByStock,
                                    Map<Integer, TornStockMonthlyStateDO> monthlyByStock,
                                    StockBuySignalEvaluator.SignalEvaluation evaluation,
                                    LocalDateTime t) {
        TornStockPortfolioSlotDO slot = portfolio.firstAvailableSlot();
        if (slot == null) {
            handleCapacityReject(evaluation, REJECT_PORTFOLIO_FULL, candidateRank, t,
                    barByStock.get(candidate.stocksId()));
            return;
        }
        TornStockMarketBar15mDO bar = barByStock.get(candidate.stocksId());
        if (bar == null || bar.getLastPrice() == null || bar.getLastPrice().signum() <= 0) {
            handleCapacityReject(evaluation, "DATA_NOT_READY", candidateRank, t, bar);
            return;
        }
        Long quantity = StockPortfolioService.calculateQuantity(slot.getAvailableCash(), bar.getLastPrice());
        if (quantity == null || quantity <= 0) {
            handleCapacityReject(evaluation, REJECT_INSUFFICIENT_FUNDS, candidateRank, t, bar);
            return;
        }
        acceptFormalCandidate(candidate, slot, monthlyByStock.get(candidate.stocksId()),
                bar.getLastPrice(), quantity, t);
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

    private void handleCapacityReject(StockBuySignalEvaluator.SignalEvaluation evaluation,
                                      String rejectReason, int candidateRank, LocalDateTime t,
                                      TornStockMarketBar15mDO bar) {
        if (collectShadow && evaluation != null && evaluation.edgeTriggered() && isUsablePrice(bar)) {
            createShadowBatch(evaluation, bar.getLastPrice(), t);
        }
        if (collectObservations && evaluation != null) {
            observations.add(StockReplayObservationCandidate.ofRejection(evaluation,
                    mapCapacityReason(rejectReason), candidateRank, t));
        }
    }

    private static String mapCapacityReason(String rejectReason) {
        return REJECT_PORTFOLIO_FULL.equals(rejectReason)
                || REJECT_INSUFFICIENT_FUNDS.equals(rejectReason)
                ? REJECT_PORTFOLIO_FULL : rejectReason;
    }

    private static boolean isUsablePrice(TornStockMarketBar15mDO bar) {
        return bar != null && bar.getLastPrice() != null && bar.getLastPrice().signum() > 0;
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
            observations.add(StockReplayObservationCandidate.ofRawBuy(evaluation, t));
            if (evaluation.context() != null
                    && StockRiskLevelEnum.HIGH == evaluation.context().riskLevel()) {
                observations.add(StockReplayObservationCandidate.ofHighRisk(evaluation, t));
            }
            if (evaluation.eligibilityResult() != null
                    && StockEligibilityResultEnum.REJECTED == evaluation.eligibilityResult().result()) {
                observations.add(StockReplayObservationCandidate.ofRejection(
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
            case "NO_AVAILABLE_SLOT", REJECT_INSUFFICIENT_FUNDS -> REJECT_PORTFOLIO_FULL;
            default -> reason;
        };
    }

    // ==================== 交易记录 ====================

    private void recordBuyTrade(TornStockVirtualBatchDO batch, LocalDateTime t) {
        String ledgerType = batch.getLedgerType();
        if (StockLedgerTypeEnum.FORMAL.getCode().equals(ledgerType)) {
            metrics.onFormalBuy();
            tradesByTrack.get(track.getCode()).add(new StockReplayTrade(
                    runId, track.getCode(), t, batch.getStocksId(), batch.getStocksShortname(),
                    "BUY", batch.getPrimaryStrategy(), batch.getSignalTime(), batch.getEntryTime(), null,
                    batch.getQuantity(), batch.getEntryReferencePrice(), null,
                    batch.getInvestedCash(), null, null, null, null, batch.getBatchNo(), null));
        } else if (collectShadow
                && StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(ledgerType)) {
            String code = StockReplayTrackEnum.UNLIMITED_SHADOW.getCode();
            tradesByTrack.get(code).add(new StockReplayTrade(
                    runId, code, t, batch.getStocksId(), batch.getStocksShortname(),
                    "BUY", batch.getPrimaryStrategy(), batch.getSignalTime(), batch.getEntryTime(), null,
                    batch.getQuantity(), batch.getEntryReferencePrice(), null,
                    batch.getInvestedCash(), null, null, null, null, batch.getBatchNo(), null));
        }
    }

    private void recordSellTrade(TornStockVirtualBatchDO batch, LocalDateTime t) {
        String ledgerType = batch.getLedgerType();
        String trackCode;
        if (StockLedgerTypeEnum.FORMAL.getCode().equals(ledgerType)) {
            trackCode = track.getCode();
            metrics.onFormalSell(batch);
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

    // ==================== 收尾: 理论观察与摘要 ====================

    private void finish() {
        for (StockReplayObservationCandidate observation : observations) {
            emitRejection(observation);
        }
    }

    private void emitRejection(StockReplayObservationCandidate observation) {
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

    private StockReplayRejection toRejectionRow(StockReplayObservationCandidate observation,
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
            StockReplayObservationCandidate observation) {
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
        return summaryBuilder.build(windowDays);
    }

    /**
     * 动态SELL研究数据摘要。
     *
     * @return 动态SELL研究摘要
     */
    public StockReplaySummary.DynamicSellSummary dynamicSellSummary() {
        return dynamicResearch.summary();
    }

    private static final java.time.format.DateTimeFormatter FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm");
}
