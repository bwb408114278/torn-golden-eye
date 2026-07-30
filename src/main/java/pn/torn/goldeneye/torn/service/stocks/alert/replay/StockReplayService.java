package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockReplayBoundary;
import pn.torn.goldeneye.torn.service.stocks.alert.StockRejectedObservationCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 股票隔离回放服务入口。
 *
 * <p>回放只读取显式输入，决策和资金状态均在内存中完成，不访问正式写入编排、
 * 不发送通知、不读取系统当前时间。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public class StockReplayService {
    private static final int MONEY_SCALE = 18;
    private static final long SHADOW_QUANTITY = 1L;
    private static final String SHADOW_STATUS = "SHADOW_NO_FORMAL_EQUITY";
    private static final String OBSERVATION_STATUS = "OBSERVATION_ONLY";
    private final StockReplayArtifactWriter artifactWriter;
    private final StockReplayInputLoader inputLoader;
    private final StockReplayDecisionEngine decisionEngine;
    private final StockReplayPortfolioEngine portfolioEngine;

    public StockReplayService(StockReplayArtifactWriter artifactWriter) {
        this(artifactWriter, null, null);
    }

    public StockReplayService(StockReplayArtifactWriter artifactWriter,
                              StockReplayInputLoader inputLoader,
                              StockReplayDecisionEngine decisionEngine) {
        this.artifactWriter = Objects.requireNonNull(artifactWriter, "artifactWriter不能为空");
        this.inputLoader = inputLoader;
        this.decisionEngine = decisionEngine;
        this.portfolioEngine = new StockReplayPortfolioEngine();
    }

    /**
     * 执行一次隔离回放。
     *
     * @param request 显式回放请求
     * @return 回放结果
     * @throws IOException 研究产物写入异常
     */
    public StockReplayResult run(StockReplayRequest request) throws IOException {
        StockReplayContext replayContext = StockReplayContext.create(request);
        StockReplayBoundary boundary = replayContext.boundary();
        if (inputLoader == null || decisionEngine == null) {
            return writeMissingDependencyResult(request, boundary);
        }
        StockReplayInput input = inputLoader.load(request);
        ReplayAccumulator accumulator = new ReplayAccumulator(boundary);
        for (StockReplayTrackEnum track : orderedTracks(request)) {
            runTrack(request, replayContext, input, accumulator, track);
        }
        StockReplaySummary summary = buildSummary(request, accumulator);
        artifactWriter.write(summary, accumulator.trades, accumulator.rejections,
                accumulator.equityPoints, request.outputDirectory());
        return result(boundary, summary.status());
    }

    private List<StockReplayTrackEnum> orderedTracks(StockReplayRequest request) {
        return request.tracks().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
    }

    private void runTrack(StockReplayRequest request, StockReplayContext replayContext,
                          StockReplayInput input, ReplayAccumulator accumulator,
                          StockReplayTrackEnum track) {
        if (track == StockReplayTrackEnum.FORMAL_5_SLOT) {
            runFormalTrack(request, replayContext, input, accumulator);
            return;
        }
        if (track == StockReplayTrackEnum.REJECTED_OBSERVATION) {
            runRejectedObservationTrack(request, input, accumulator);
            return;
        }
        runShadowTrack(request, input, accumulator, track);
    }

    private void runFormalTrack(StockReplayRequest request, StockReplayContext replayContext,
                                StockReplayInput input, ReplayAccumulator accumulator) {
        StockReplayPortfolioState state = replayContext.portfolioState(StockReplayTrackEnum.FORMAL_5_SLOT);
        Map<Integer, TornReplayOpenPosition> openPositions = new HashMap<>();
        Map<Integer, TornReplayPendingEntry> pendingEntries = new HashMap<>();
        Map<Integer, TornReplayPendingExit> pendingExits = new HashMap<>();
        Map<BarKey, TornStockMarketBar15mDO> barIndex = indexBars(input.bars());
        Map<Integer, TornStockMonthlyStateDO> monthlyStates = indexMonthlyStates(input.monthlyStates());
        for (TornStockStrategyFeature15mDO feature : sortedFeatures(input.features())) {
            if (!isFeatureProcessable(feature)) {
                continue;
            }
            LocalDateTime featureTime = feature.getBarStartTime();
            TornStockMarketBar15mDO currentBar = barIndex.get(new BarKey(feature.getStocksId(), featureTime));
            TornReplayPendingEntry pendingEntry = pendingEntries.get(feature.getStocksId());
            if (pendingEntry != null && featureTime.isAfter(pendingEntry.expectedTime())) {
                pendingEntries.remove(feature.getStocksId());
                state = portfolioEngine.releaseReserved(state, pendingEntry.slotNo());
                accumulator.rejections.add(rejection(accumulator.boundary,
                        StockReplayTrackEnum.FORMAL_5_SLOT, feature, "ENTRY_DATA_STALE"));
            } else if (pendingEntry != null && pendingEntry.expectedTime().equals(featureTime)) {
                pendingEntries.remove(feature.getStocksId());
                if (isValidPriceBar(currentBar)) {
                StockReplayPortfolioEngine.EntryResult entry = portfolioEngine.enterReserved(
                        state, pendingEntry.slotNo(), feature.getStocksId(), currentBar.getLastPrice());
                state = entry.state();
                StockReplaySlotState slot = findSlot(state, pendingEntry.slotNo());
                openPositions.put(feature.getStocksId(), new TornReplayOpenPosition(
                        StockReplayTrackEnum.FORMAL_5_SLOT, feature.getStocksId(), feature.getBarStartTime(),
                        currentBar.getLastPrice(), slot.quantity(), pendingEntry.slotNo(),
                        pendingEntry.strategyCode(), createReplayBatch(feature,
                        pendingEntry.strategyCode(), currentBar.getLastPrice())));
                } else {
                    state = portfolioEngine.releaseReserved(state, pendingEntry.slotNo());
                    accumulator.rejections.add(rejection(accumulator.boundary,
                            StockReplayTrackEnum.FORMAL_5_SLOT, feature, "ENTRY_DATA_STALE"));
                }
            }
            TornReplayPendingExit pendingExit = pendingExits.get(feature.getStocksId());
            if (pendingExit != null && featureTime.isAfter(pendingExit.expectedTime())) {
                pendingExits.remove(feature.getStocksId());
                accumulator.rejections.add(rejection(accumulator.boundary,
                        StockReplayTrackEnum.FORMAL_5_SLOT, feature, "DATA_STALE_EXIT"));
            } else if (pendingExit != null && pendingExit.expectedTime().equals(featureTime)) {
                pendingExits.remove(feature.getStocksId());
                TornReplayOpenPosition open = openPositions.remove(feature.getStocksId());
                if (open != null && isValidPriceBar(currentBar)) {
                    state = portfolioEngine.exit(state, open.slotNo(), currentBar.getLastPrice());
                    accumulator.trades.add(toTrade(accumulator.boundary, open,
                            currentBar.getLastPrice(), feature.getBarStartTime(), pendingExit.closeType()));
                } else if (open != null) {
                    openPositions.put(feature.getStocksId(), open);
                    accumulator.rejections.add(rejection(accumulator.boundary,
                            StockReplayTrackEnum.FORMAL_5_SLOT, feature, "DATA_STALE_EXIT"));
                }
            }
            if (!isValidPriceBar(currentBar)) {
                accumulator.rejections.add(rejection(accumulator.boundary,
                        StockReplayTrackEnum.FORMAL_5_SLOT, feature, "DATA_INSUFFICIENT"));
                addFormalEquity(accumulator, feature, state, openPositions, barIndex);
                continue;
            }
            TornReplayOpenPosition open = openPositions.get(feature.getStocksId());
            if (open != null) {
                StockBatchExitService.ExitEvaluation exit = evaluateExit(open, feature, currentBar);
                if (exit.shouldExit()) {
                    pendingExits.put(feature.getStocksId(), new TornReplayPendingExit(
                            featureTime.plusMinutes(15), exit.closeType().getCode()));
                }
            }
            if (!openPositions.containsKey(feature.getStocksId())
                    && !pendingEntries.containsKey(feature.getStocksId())) {
                BuyContext buyContext = toBuyContext(feature, monthlyStates.get(feature.getStocksId()));
                StockReplayDecisionEngine.BuyDecision decision = decisionEngine.evaluateBuyWithSignalStates(
                        buyContext, input.signalStates(), request.buyRuleVersion(), false,
                        feature.getBarStartTime());
                if (decision.accepted()) {
                    StockReplayDecisionEngine.Decision allocation = decisionEngine.allocateFormal(
                        state, feature.getStocksId(), currentBar.getLastPrice(), feature.getBarStartTime());
                    if ("ACCEPTED".equals(allocation.reason())) {
                        state = allocation.state();
                        pendingEntries.put(feature.getStocksId(), new TornReplayPendingEntry(
                            feature.getBarStartTime().plusMinutes(15), allocation.slotNo(),
                            decision.strategyCode()));
                    } else {
                        accumulator.rejections.add(rejection(accumulator.boundary,
                                StockReplayTrackEnum.FORMAL_5_SLOT, feature, allocation.reason()));
                    }
                } else {
                    accumulator.rejections.add(rejection(accumulator.boundary,
                            StockReplayTrackEnum.FORMAL_5_SLOT, feature, decision.reason()));
                }
            }
            addFormalEquity(accumulator, feature, state, openPositions, barIndex);
        }
    }

    private void runShadowTrack(StockReplayRequest request, StockReplayInput input,
                                ReplayAccumulator accumulator, StockReplayTrackEnum track) {
        Map<Integer, TornReplayOpenPosition> openPositions = new HashMap<>();
        Map<Integer, TornReplayPendingEntry> pendingEntries = new HashMap<>();
        Map<Integer, TornReplayPendingExit> pendingExits = new HashMap<>();
        Map<BarKey, TornStockMarketBar15mDO> barIndex = indexBars(input.bars());
        Map<Integer, TornStockMonthlyStateDO> monthlyStates = indexMonthlyStates(input.monthlyStates());
        for (TornStockStrategyFeature15mDO feature : sortedFeatures(input.features())) {
            if (!isFeatureProcessable(feature)) {
                continue;
            }
            LocalDateTime featureTime = feature.getBarStartTime();
            TornStockMarketBar15mDO currentBar = barIndex.get(new BarKey(
                    feature.getStocksId(), featureTime));
            TornReplayPendingEntry pendingEntry = pendingEntries.get(feature.getStocksId());
            if (pendingEntry != null && featureTime.isAfter(pendingEntry.expectedTime())) {
                pendingEntries.remove(feature.getStocksId());
                accumulator.rejections.add(rejection(accumulator.boundary, track, feature,
                        "ENTRY_DATA_STALE"));
            } else if (pendingEntry != null && pendingEntry.expectedTime().equals(featureTime)) {
                pendingEntries.remove(feature.getStocksId());
                if (isValidPriceBar(currentBar)) {
                openPositions.put(feature.getStocksId(), new TornReplayOpenPosition(track,
                        feature.getStocksId(), feature.getBarStartTime(), currentBar.getLastPrice(),
                        SHADOW_QUANTITY, 0, pendingEntry.strategyCode(), createReplayBatch(feature,
                        pendingEntry.strategyCode(), currentBar.getLastPrice())));
                } else {
                    accumulator.rejections.add(rejection(accumulator.boundary, track, feature,
                            "ENTRY_DATA_STALE"));
                }
            }
            TornReplayPendingExit pendingExit = pendingExits.get(feature.getStocksId());
            if (pendingExit != null && featureTime.isAfter(pendingExit.expectedTime())) {
                pendingExits.remove(feature.getStocksId());
                accumulator.rejections.add(rejection(accumulator.boundary, track, feature,
                        "DATA_STALE_EXIT"));
            } else if (pendingExit != null && pendingExit.expectedTime().equals(featureTime)) {
                pendingExits.remove(feature.getStocksId());
                TornReplayOpenPosition open = openPositions.remove(feature.getStocksId());
                if (open != null && isValidPriceBar(currentBar)) {
                    accumulator.trades.add(toTrade(accumulator.boundary, open,
                        currentBar.getLastPrice(), feature.getBarStartTime(), pendingExit.closeType()));
                } else if (open != null) {
                    openPositions.put(feature.getStocksId(), open);
                    accumulator.rejections.add(rejection(accumulator.boundary, track, feature,
                            "DATA_STALE_EXIT"));
                }
            }
            if (!isValidPriceBar(currentBar)) {
                accumulator.rejections.add(rejection(accumulator.boundary, track, feature,
                        "DATA_INSUFFICIENT"));
                addShadowEquity(accumulator, track, feature, SHADOW_STATUS);
                continue;
            }
            TornReplayOpenPosition open = openPositions.get(feature.getStocksId());
            if (open != null) {
                StockBatchExitService.ExitEvaluation exit = evaluateExit(open, feature, currentBar);
                if (exit.shouldExit()) {
                    pendingExits.put(feature.getStocksId(), new TornReplayPendingExit(
                            featureTime.plusMinutes(15), exit.closeType().getCode()));
                }
            }
            if (!openPositions.containsKey(feature.getStocksId())
                    && !pendingEntries.containsKey(feature.getStocksId())) {
                BuyContext buyContext = toBuyContext(feature, monthlyStates.get(feature.getStocksId()));
                StockReplayDecisionEngine.BuyDecision decision = decisionEngine.evaluateBuyWithSignalStates(
                        buyContext, input.signalStates(), request.buyRuleVersion(), false,
                        feature.getBarStartTime());
                if (decision.accepted()) {
                    pendingEntries.put(feature.getStocksId(), new TornReplayPendingEntry(
                            feature.getBarStartTime().plusMinutes(15), 0, decision.strategyCode()));
                } else {
                    accumulator.rejections.add(rejection(accumulator.boundary, track, feature,
                            decision.reason()));
                }
            }
            addShadowEquity(accumulator, track, feature, SHADOW_STATUS);
        }
    }

    private void runRejectedObservationTrack(StockReplayRequest request, StockReplayInput input,
                                              ReplayAccumulator accumulator) {
        Map<Integer, TornStockMonthlyStateDO> monthlyStates = indexMonthlyStates(input.monthlyStates());
        for (TornStockStrategyFeature15mDO feature : sortedFeatures(input.features())) {
            if (!isFeatureProcessable(feature)) {
                continue;
            }
            BuyContext buyContext = toBuyContext(feature, monthlyStates.get(feature.getStocksId()));
            StockReplayDecisionEngine.BuyDecision decision = decisionEngine.evaluateBuyWithSignalStates(
                    buyContext, input.signalStates(), request.buyRuleVersion(), false,
                    feature.getBarStartTime());
            if (decision.accepted()) {
                addShadowEquity(accumulator, StockReplayTrackEnum.REJECTED_OBSERVATION,
                        feature, OBSERVATION_STATUS);
                continue;
            }
            TornStockSignalEventDO event = buildObservationEvent(feature, decision.reason());
            TornStockVirtualBatchDO batch = buildObservationBatch(feature);
            StockRejectedObservationCalculator.Result observation =
                    StockRejectedObservationCalculator.calculate(event, batch, input.bars());
            accumulator.rejections.add(new StockReplayRejection(accumulator.boundary.runId(),
                    StockReplayTrackEnum.REJECTED_OBSERVATION, feature.getStocksId(), decision.strategyCode(),
                    feature.getBarStartTime(), decision.reason(), observation.resultCode(),
                    decimal(observation.laterMfe()), decimal(observation.laterMae())));
            addShadowEquity(accumulator, StockReplayTrackEnum.REJECTED_OBSERVATION,
                    feature, OBSERVATION_STATUS);
        }
    }

    private StockBatchExitService.ExitEvaluation evaluateExit(TornReplayOpenPosition open,
                                                               TornStockStrategyFeature15mDO feature,
                                                               TornStockMarketBar15mDO currentBar) {
        return decisionEngine.evaluateSell(open.batch(), currentBar.getLastPrice(),
                feature.getPosition30(), feature.getLow30d(), feature.getHigh30d(),
                feature.getBarStartTime());
    }

    private void addFormalEquity(ReplayAccumulator accumulator, TornStockStrategyFeature15mDO feature,
                                 StockReplayPortfolioState state,
                                 Map<Integer, TornReplayOpenPosition> openPositions,
                                 Map<BarKey, TornStockMarketBar15mDO> barIndex) {
        List<StockReplayPortfolioEngine.PricePoint> prices = openPositions.values().stream()
                .map(open -> new StockReplayPortfolioEngine.PricePoint(open.stocksId(),
                        priceAt(barIndex, open.stocksId(), feature.getBarStartTime())))
                .toList();
        StockReplayPortfolioEngine.EquityResult equity = portfolioEngine.calculateEquity(state, prices);
        accumulator.equityPoints.add(new StockReplayEquityPoint(accumulator.boundary.runId(),
                StockReplayTrackEnum.FORMAL_5_SLOT, feature.getBarStartTime(), decimal(equity.equity()),
                "", "", equity.status(), ""));
    }

    private void addShadowEquity(ReplayAccumulator accumulator, StockReplayTrackEnum track,
                                 TornStockStrategyFeature15mDO feature, String status) {
        accumulator.equityPoints.add(new StockReplayEquityPoint(accumulator.boundary.runId(), track,
                feature.getBarStartTime(), "", "", "", status, ""));
    }

    private Map<BarKey, TornStockMarketBar15mDO> indexBars(List<TornStockMarketBar15mDO> bars) {
        Map<BarKey, TornStockMarketBar15mDO> result = new HashMap<>();
        for (TornStockMarketBar15mDO bar : bars) {
            if (bar != null && bar.getStocksId() != null && bar.getBarStartTime() != null) {
                result.put(new BarKey(bar.getStocksId(), bar.getBarStartTime()), bar);
            }
        }
        return result;
    }

    private Map<Integer, TornStockMonthlyStateDO> indexMonthlyStates(List<TornStockMonthlyStateDO> states) {
        Map<Integer, TornStockMonthlyStateDO> result = new HashMap<>();
        for (TornStockMonthlyStateDO state : states) {
            if (state != null && state.getStocksId() != null) {
                result.putIfAbsent(state.getStocksId(), state);
            }
        }
        return result;
    }

    private List<TornStockStrategyFeature15mDO> sortedFeatures(List<TornStockStrategyFeature15mDO> features) {
        return features.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(TornStockStrategyFeature15mDO::getBarStartTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TornStockStrategyFeature15mDO::getStocksId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private boolean isFeatureProcessable(TornStockStrategyFeature15mDO feature) {
        return feature.getStocksId() != null && feature.getBarStartTime() != null;
    }

    private boolean isValidPriceBar(TornStockMarketBar15mDO bar) {
        return bar != null && bar.getLastPrice() != null && bar.getLastPrice().signum() > 0;
    }

    private BigDecimal priceAt(Map<BarKey, TornStockMarketBar15mDO> barIndex,
                               Integer stocksId, LocalDateTime time) {
        TornStockMarketBar15mDO bar = barIndex.get(new BarKey(stocksId, time));
        return isValidPriceBar(bar) ? bar.getLastPrice() : null;
    }

    private StockReplaySlotState findSlot(StockReplayPortfolioState state, int slotNo) {
        return state.slots().stream().filter(slot -> slot.slotNo() == slotNo).findFirst()
                .orElseThrow(() -> new IllegalStateException("分配后的槽位不存在: " + slotNo));
    }

    private TornStockSignalEventDO buildObservationEvent(TornStockStrategyFeature15mDO feature,
                                                           String rejectReason) {
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setRoundTime(feature.getBarStartTime());
        event.setStocksId(feature.getStocksId());
        event.setStocksShortname(feature.getStocksShortname());
        event.setSignalReferencePrice(feature.getReferencePrice());
        event.setRejectReason(rejectReason);
        event.setPortfolioDecision("REJECTED");
        return event;
    }

    private TornStockVirtualBatchDO buildObservationBatch(TornStockStrategyFeature15mDO feature) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setExpectedEntryBarTime(feature.getBarStartTime().plusMinutes(15));
        batch.setEntryStaleAt(feature.getBarStartTime().plusMinutes(30));
        batch.setSignalReferencePrice(feature.getReferencePrice());
        return batch;
    }

    private TornStockVirtualBatchDO createReplayBatch(TornStockStrategyFeature15mDO feature,
                                                       String strategyCode, BigDecimal entryPrice) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(feature.getStocksId());
        batch.setPrimaryStrategy(strategyCode);
        batch.setBatchStatus("OPEN");
        batch.setEntryTime(feature.getBarStartTime());
        batch.setEntryReferencePrice(entryPrice);
        return batch;
    }

    private BuyContext toBuyContext(TornStockStrategyFeature15mDO feature,
                                    TornStockMonthlyStateDO monthlyState) {
        return new BuyContext(feature.getStocksId(), feature.getStocksShortname(), feature.getReferencePrice(),
                feature.getMa1d(), feature.getMa7d(), feature.getMa30d(), feature.getZscore1d(),
                feature.getZscore7d(), feature.getZscore30d(), feature.getReturn6h(), feature.getReturn1d(),
                feature.getReturn7d(), feature.getReturn14d(), feature.getLow30d(), feature.getHigh30d(),
                feature.getWidth30d(), feature.getPosition30(), feature.getPctAbove30dLow(),
                feature.getPctBelow30dHigh(), feature.getStrategyReady(), parseStyle(monthlyState),
                parseMaturity(monthlyState), parseRisk(monthlyState));
    }

    private StockStrategyFitEnum parseStyle(TornStockMonthlyStateDO state) {
        return parseEnum(state == null ? null : state.getStrategyFitPrior(), StockStrategyFitEnum.class);
    }

    private StockMaturityEnum parseMaturity(TornStockMonthlyStateDO state) {
        return parseEnum(state == null ? null : state.getMaturity(), StockMaturityEnum.class);
    }

    private StockRiskLevelEnum parseRisk(TornStockMonthlyStateDO state) {
        return parseEnum(state == null ? null : state.getRiskLevel(), StockRiskLevelEnum.class);
    }

    private <E extends Enum<E>> E parseEnum(String code, Class<E> enumType) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, code);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private StockReplayRejection rejection(StockReplayBoundary boundary, StockReplayTrackEnum track,
                                           TornStockStrategyFeature15mDO feature, String reason) {
        return new StockReplayRejection(boundary.runId(), track, feature.getStocksId(), null,
                feature.getBarStartTime(), reason, null, null, null);
    }

    private StockReplayTrade toTrade(StockReplayBoundary boundary, TornReplayOpenPosition open,
                                     BigDecimal exitPrice, LocalDateTime exitTime, String closeType) {
        BigDecimal grossReturn = exitPrice.divide(open.entryPrice(), MONEY_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        BigDecimal netReturn = grossReturn.add(BigDecimal.ONE)
                .multiply(StockReplayPortfolioEngine.SELL_PROCEEDS_RATE).subtract(BigDecimal.ONE);
        return new StockReplayTrade(boundary.runId(), open.track(), open.stocksId(), open.strategyCode(),
                open.entryTime(), exitTime, decimal(open.entryPrice()), decimal(exitPrice), open.quantity(),
                decimal(grossReturn), decimal(netReturn), closeType);
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private StockReplayResult writeMissingDependencyResult(StockReplayRequest request,
                                                           StockReplayBoundary boundary)
            throws IOException {
        String status = request.tracks().stream().allMatch(track -> !track.isFormal())
                ? "COMPLETED" : "FAILED";
        StockReplaySummary summary = new StockReplaySummary(boundary.runId(), boundary.portfolioId(),
                status, request.startTime().toString(), request.endTime().toString(),
                request.barBuildVersion(), request.featureVersion(), request.buyRuleVersion(),
                request.sellRuleVersion(), request.allocationRuleVersion(), request.messageRuleVersion(),
                request.tracks().toString(), "0", "", "", "", "", 0, 0, "FAILED", "missing replay dependencies");
        artifactWriter.write(summary, List.of(), List.of(), List.of(), request.outputDirectory());
        return result(boundary, status);
    }

    private StockReplaySummary buildSummary(StockReplayRequest request, ReplayAccumulator accumulator) {
        StockReplaySummary summary = StockReplaySummary.fromRequest(request, accumulator.boundary,
                accumulator.trades.size(), accumulator.rejections.size(), "", "COMPLETE", "");
        return summary;
    }

    private StockReplayResult result(StockReplayBoundary boundary, String status) {
        return new StockReplayResult(boundary.runId(), boundary.portfolioId(), status,
                List.of(boundary.runId() + "-summary.json", boundary.runId() + "-trades.csv",
                        boundary.runId() + "-rejections.csv", boundary.runId() + "-equity-curve.csv"));
    }

    private record BarKey(Integer stocksId, LocalDateTime barStartTime) {
    }

    private record TornReplayPendingEntry(LocalDateTime expectedTime, int slotNo, String strategyCode) {
    }

    private record TornReplayPendingExit(LocalDateTime expectedTime, String closeType) {
    }

    private record TornReplayOpenPosition(StockReplayTrackEnum track, Integer stocksId,
                                          LocalDateTime entryTime, BigDecimal entryPrice,
                                          long quantity, int slotNo, String strategyCode,
                                          TornStockVirtualBatchDO batch) {
    }

    private static final class ReplayAccumulator {
        private final StockReplayBoundary boundary;
        private final List<StockReplayTrade> trades = new ArrayList<>();
        private final List<StockReplayRejection> rejections = new ArrayList<>();
        private final List<StockReplayEquityPoint> equityPoints = new ArrayList<>();

        private ReplayAccumulator(StockReplayBoundary boundary) {
            this.boundary = boundary;
        }
    }
}
