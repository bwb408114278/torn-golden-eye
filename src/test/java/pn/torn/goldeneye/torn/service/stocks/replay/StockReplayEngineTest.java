package pn.torn.goldeneye.torn.service.stocks.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.replay.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回放引擎纯领域单元测试(合成窗口,无数据库)。
 * <p>
 * 验证: 确定性、正式买卖生命周期、槽位不超卖、同股单活跃、晚于staleAt补发BUY为0、
 * 整数股数/0.999费口径、资金守恒、拒绝观察(无入场原因与满仓理论路径)、原始BUY对照与轨道隔离。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@DisplayName("回放引擎纯领域单元测试")
class StockReplayEngineTest {

    /**
     * 合成窗口起点(2099年远端时间,远离生产数据)。
     */
    private static final LocalDateTime T0 = LocalDateTime.of(2099, 1, 5, 10, 0);
    /**
     * 窗口轮次数。
     */
    private static final int ROUNDS = 8;
    /**
     * 每轮价格: 信号→入场→区间内→目标信号→目标成交→回落。
     */
    private static final BigDecimal[] PRICES = {
            new BigDecimal("95.2"), new BigDecimal("95.2"), new BigDecimal("95.5"),
            new BigDecimal("96.3"), new BigDecimal("96.5"),
            new BigDecimal("96.0"), new BigDecimal("96.0"), new BigDecimal("96.0")
    };
    /**
     * 匹配RANGE策略的股票数量(第6只为满仓溢出,触发Shadow与PORTFOLIO_FULL)。
     */
    private static final int MATCH_STOCKS = 6;
    /**
     * 成熟度不足被拒绝的股票。
     */
    private static final int IMMATURE_STOCK = 7;

    @Test
    @DisplayName("相同输入两次运行产物完全一致(确定性)")
    void run_sameInput_identicalOutputs() {
        StockReplayContext context = buildContext("run-deterministic");

        StockReplayEngine first = new StockReplayEngine(StockReplayTrackEnum.FORMAL_20E, "run-deterministic", context);
        StockReplayEngine second = new StockReplayEngine(StockReplayTrackEnum.FORMAL_20E, "run-deterministic", context);
        first.run();
        second.run();

        assertEquals(first.tradesByTrack(), second.tradesByTrack(), "两次运行交易记录应完全一致");
        assertEquals(first.rejectionsByTrack(), second.rejectionsByTrack(), "两次运行拒绝/观察记录应完全一致");
        assertEquals(first.equityByTrack(), second.equityByTrack(), "两次运行净值点应完全一致");
    }

    @Test
    @DisplayName("正式轨道买卖生命周期完整且口径正确")
    void formalTrack_buySellLifecycleAndGates() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_20E);

        List<StockReplayTrade> formalTrades = engine.tradesByTrack().get(StockReplayTrackEnum.FORMAL_20E.getCode());
        assertNotNull(formalTrades);
        List<StockReplayTrade> buys = formalTrades.stream().filter(t -> "BUY".equals(t.side())).toList();
        List<StockReplayTrade> sells = formalTrades.stream().filter(t -> "SELL".equals(t.side())).toList();
        assertEquals(MATCH_STOCKS - 1, buys.size(), "第6只候选因满仓转影子,正式应成交5笔买入");
        assertEquals(MATCH_STOCKS - 1, sells.size(), "正式应完成5笔卖出");

        for (StockReplayTrade buy : buys) {
            assertNotNull(buy.quantity());
            assertTrue(buy.quantity() > 0, "股数必须为正整数");
            assertEquals("F" + buy.signalTime().format(REPLAY_TS) + buy.stocksId(), buy.batchNo(),
                    "正式批次编号前缀F+信号时间+股票ID");
            assertNotNull(buy.entryTime());
            assertTrue(buy.entryTime().isBefore(buy.signalTime().plusMinutes(35)), "成交时间必须早于入场过期时间");
        }
        for (StockReplayTrade sell : sells) {
            assertEquals("CLOSED_TARGET", sell.closeType(), "应通过目标+0.8%退出");
            BigDecimal expectedNetReturn = sell.exitPrice().divide(sell.entryPrice(), 18, java.math.RoundingMode.HALF_UP)
                    .multiply(StockReplayEngineTest.FEE).subtract(BigDecimal.ONE);
            assertEquals(0, expectedNetReturn.compareTo(sell.netReturn()), "净收益必须按0.1%卖出费计算");
        }
    }

    @Test
    @DisplayName("槽位不超卖且资金守恒(available+reserved+持仓市值=初始+已实现)")
    void formalTrack_cashConservationAndNoOversold() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_20E);

        List<StockReplayEquityPoint> points = engine.equityByTrack().get(StockReplayTrackEnum.FORMAL_20E.getCode());
        assertFalse(points.isEmpty());
        for (StockReplayEquityPoint point : points) {
            if (point.utilization() != null) {
                assertTrue(point.utilization().compareTo(BigDecimal.ONE) <= 0, "槽位占用率不得超过1");
            }
        }

        StockReplayEquityPoint last = points.getLast();
        BigDecimal realized = engine.tradesByTrack().get(StockReplayTrackEnum.FORMAL_20E.getCode()).stream()
                .filter(t -> "SELL".equals(t.side()))
                .map(t -> t.sellProceeds().subtract(t.investedCash()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal initialCash = BigDecimal.valueOf(5).multiply(new BigDecimal("2000000000.00"));
        assertEquals(0, last.equity().subtract(initialCash.add(realized)).compareTo(BigDecimal.ZERO),
                "期末权益必须等于初始资金加已实现净收益");
    }

    @Test
    @DisplayName("同股单活跃批次且不重复买入")
    void formalTrack_noDuplicateSameStock() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_20E);
        List<StockReplayTrade> buys = engine.tradesByTrack().get(StockReplayTrackEnum.FORMAL_20E.getCode()).stream()
                .filter(t -> "BUY".equals(t.side()))
                .toList();
        long distinctStocks = buys.stream().map(StockReplayTrade::stocksId).distinct().count();
        assertEquals(buys.size(), distinctStocks, "同一股票不得出现重复买入");
    }

    @Test
    @DisplayName("无限资金影子轨道承接满仓溢出信号(恒1股)")
    void shadowTrack_overflowSingleShare() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_20E);
        List<StockReplayTrade> shadowTrades =
                engine.tradesByTrack().get(StockReplayTrackEnum.UNLIMITED_SHADOW.getCode());
        assertNotNull(shadowTrades);
        assertTrue(shadowTrades.stream().anyMatch(t -> "BUY".equals(t.side())), "满仓溢出应建立影子批次");
        assertTrue(shadowTrades.stream().allMatch(t -> t.quantity() == 1L), "影子恒买1股");
    }

    @Test
    @DisplayName("拒绝观察: 成熟度不足不建理论路径,满仓建立理论路径")
    void rejectionObservation_reasonsAndPaths() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_20E);
        List<StockReplayRejection> rejections =
                engine.rejectionsByTrack().get(StockReplayTrackEnum.REJECTION_OBSERVATION.getCode());
        assertNotNull(rejections);
        boolean noEntry = rejections.stream()
                .anyMatch(r -> "MATURITY_INSUFFICIENT".equals(r.rejectReason())
                        && "NO_THEORETICAL_ENTRY".equals(r.observationResult()));
        boolean fullPath = rejections.stream()
                .anyMatch(r -> "PORTFOLIO_FULL".equals(r.rejectReason()) && r.observationResult() != null);
        assertTrue(noEntry, "成熟度不足应记录为无法理论入场");
        assertTrue(fullPath, "满仓拒绝应计算理论路径");
    }

    @Test
    @DisplayName("原始BUY对照与高风险观察馈送存在")
    void observationFeeds_rawBuyAndHighRisk() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_20E);
        List<StockReplayRejection> rawBuy =
                engine.rejectionsByTrack().get(StockReplayTrackEnum.RAW_BUY_CONTROL.getCode());
        assertNotNull(rawBuy);
        assertEquals(MATCH_STOCKS + 1, rawBuy.size(), "每个边沿命中候选均应记录原始BUY对照");

        List<StockReplayRejection> highRisk =
                engine.rejectionsByTrack().get(StockReplayTrackEnum.HIGH_RISK_OBSERVATION.getCode());
        assertNotNull(highRisk);
        assertEquals(0, highRisk.size(), "合成窗口无HIGH风险候选");
    }

    @Test
    @DisplayName("历史对照轨道独立且不产出影子/观察")
    void formal4e_trackIsolation() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_4E);
        assertFalse(engine.tradesByTrack().containsKey(StockReplayTrackEnum.UNLIMITED_SHADOW.getCode()),
                "4亿对照轨道不得产出影子");
        assertFalse(engine.rejectionsByTrack().containsKey(StockReplayTrackEnum.REJECTION_OBSERVATION.getCode()),
                "4亿对照轨道不得产出拒绝观察");
        List<StockReplayTrade> trades = engine.tradesByTrack().get(StockReplayTrackEnum.FORMAL_4E.getCode());
        assertTrue(trades.stream().allMatch(t -> StockReplayTrackEnum.FORMAL_4E.getCode().equals(t.track())),
                "全部交易必须归属4亿对照轨道");
    }

    @Test
    @DisplayName("动态SELL研究数据公式冻结前不产生建议与交易")
    void dynamicSell_researchDataOnly() {
        StockReplayEngine engine = runEngine(StockReplayTrackEnum.FORMAL_20E);
        StockReplaySummary.DynamicSellSummary dynamic = engine.dynamicSellSummary();
        assertEquals("NOT_EVALUATED", dynamic.decision(), "动态决定必须固定NOT_EVALUATED");
        assertEquals("DYNAMIC_RULE_NOT_FROZEN", dynamic.reason(), "动态原因必须固定DYNAMIC_RULE_NOT_FROZEN");
        assertEquals(0, dynamic.suggestions(), "公式冻结前不得产生动态建议");
        assertEquals(0, dynamic.trades(), "公式冻结前不得产生动态交易");
        assertEquals(0, dynamic.closes(), "公式冻结前不得产生动态关闭");
        assertTrue(dynamic.observations() > 0, "应采集开放批次研究输入");
    }

    @Test
    @DisplayName("RANGE绝对趋势守卫失败_仍写原始BUY对照与拒绝观察原因正确")
    void rangeGuard_failure_rawBuyAndRejectionObservation() {
        LocalDateTime start = T0;
        StockReplayRequest request = new StockReplayRequest(
                start, start, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.values()), "target/replay-unit/guard-fail");
        StockReplayEngine engine = new StockReplayEngine(StockReplayTrackEnum.FORMAL_20E, "guard-fail",
                new StockReplayContext(request, guardFailWindowData(start)));
        engine.run();

        List<StockReplayRejection> rawBuy =
                engine.rejectionsByTrack().get(StockReplayTrackEnum.RAW_BUY_CONTROL.getCode());
        List<StockReplayRejection> rejections =
                engine.rejectionsByTrack().get(StockReplayTrackEnum.REJECTION_OBSERVATION.getCode());
        assertNotNull(rawBuy);
        assertEquals(1, rawBuy.size(), "守卫失败信号仍应记录原始BUY对照");
        assertEquals("RAW_BUY_SIGNAL", rawBuy.getFirst().rejectReason());

        assertNotNull(rejections);
        assertTrue(rejections.stream()
                        .anyMatch(r -> RangeLowerBuyStrategy.ABSOLUTE_TREND_GUARD_FAILED.equals(r.rejectReason())),
                "拒绝观察账本原因必须为ABSOLUTE_TREND_GUARD_FAILED");
        assertTrue(rejections.stream()
                        .anyMatch(r -> RangeLowerBuyStrategy.ABSOLUTE_TREND_GUARD_FAILED.equals(r.rejectReason())
                                && "REJECTED".equals(r.eligibilityResult())),
                "拒绝观察资格结果必须为REJECTED");
    }

    @Test
    @DisplayName("回放_RANGE趋势输入数据不足_窗口内存在后续bar_仍固定写无法理论入场")
    void rejectionObservation_dataInsufficient_noTheoreticalEntryEvenWithLaterBars() {
        LocalDateTime start = T0;
        LocalDateTime end = start.plusMinutes(3L * Stock15mBarBuildService.BUCKET_MINUTES);
        StockReplayRequest request = new StockReplayRequest(
                start, end, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.values()), "target/replay-unit/data-insufficient");
        StockReplayEngine engine = new StockReplayEngine(StockReplayTrackEnum.FORMAL_20E, "data-insufficient",
                new StockReplayContext(request, dataInsufficientWindowData(start)));
        engine.run();

        List<StockReplayRejection> rejections =
                engine.rejectionsByTrack().get(StockReplayTrackEnum.REJECTION_OBSERVATION.getCode());
        assertNotNull(rejections);
        StockReplayRejection rejection = rejections.stream()
                .filter(r -> RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT.equals(r.rejectReason()))
                .findFirst()
                .orElse(null);
        assertNotNull(rejection, "回放拒绝观察必须存在DATA_INSUFFICIENT记录");
        assertEquals("NO_THEORETICAL_ENTRY", rejection.observationResult(),
                "数据不足不得构造理论入场");
        assertNull(rejection.laterMfe(), "数据不足不得计算MFE");
        assertNull(rejection.laterMae(), "数据不足不得计算MAE");
        assertNull(rejection.theoreticalEntryTime());
        assertNull(rejection.theoreticalEntryPrice());
        assertNull(rejection.theoreticalExitSignalTime());
        assertNull(rejection.theoreticalExitTime());
        assertNull(rejection.theoreticalExitPrice());
        assertNull(rejection.theoreticalCloseType());
        assertNull(rejection.theoreticalNetReturn());
    }

    @Test
    @DisplayName("回放_处理模式决定ENTRY过期边界_在线基线与晚恢复压力")
    void entryStale_processingMode_decidesExpiryBoundary() {
        // 表驱动覆盖三个边界: 在线基线 actualProcessingTime=staleAt-1ns / =staleAt 均不因过期取消,
        // 可成交时写BUY;晚恢复压力 recoveredAt=staleAt+1ns 必须CANCELLED/ENTRY_DATA_STALE且无BUY。
        record Case(String name, StockReplayProcessingModeEnum mode, LocalDateTime staleAt,
                    LocalDateTime recoveredAt, boolean expectFill) {
        }
        List<Case> cases = List.of(
                new Case("baseline-before", StockReplayProcessingModeEnum.ONLINE_BASELINE,
                        T0.plusNanos(1), null, true),
                new Case("baseline-equal", StockReplayProcessingModeEnum.ONLINE_BASELINE,
                        T0, null, true),
                new Case("stress-after", StockReplayProcessingModeEnum.RESTART_STRESS,
                        T0.plusNanos(1), T0.plusNanos(2), false));

        for (Case testCase : cases) {
            StockReplayRequest request = new StockReplayRequest(
                    T0, T0, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                    Set.of(StockReplayTrackEnum.FORMAL_20E),
                    "target/replay-unit/entry-stale-" + testCase.name(),
                    testCase.mode(), testCase.recoveredAt());
            StockReplayEngine engine = new StockReplayEngine(StockReplayTrackEnum.FORMAL_20E,
                    "entry-stale-" + testCase.name(),
                    new StockReplayContext(request, staleBoundaryWindowData(T0)));

            TornStockVirtualBatchDO batch = seedEntryPendingAtStaleAt(engine, testCase.staleAt(), T0);
            engine.run();

            List<StockReplayTrade> buys = engine.tradesByTrack()
                    .get(StockReplayTrackEnum.FORMAL_20E.getCode()).stream()
                    .filter(t -> "BUY".equals(t.side())).toList();
            boolean staleBuy = buys.stream().anyMatch(t -> t.stocksId().equals(STALE_STOCK));
            if (testCase.expectFill()) {
                assertNotNull(batch.getEntryTime(),
                        testCase.name() + ": 实际处理时刻未晚于staleAt不得因过期取消");
                assertTrue(staleBuy, testCase.name() + ": 满足目标bar/连续性/价格偏离时应写BUY trade");
            } else {
                assertEquals(StockBatchStatusEnum.CANCELLED.getCode(), batch.getBatchStatus(),
                        testCase.name() + ": 晚于staleAt必须取消");
                assertEquals(StockCancelReasonEnum.ENTRY_DATA_STALE.getCode(), batch.getCancelReason(),
                        testCase.name() + ": 取消原因必须为ENTRY_DATA_STALE");
                assertNull(batch.getEntryTime(), testCase.name() + ": 取消批次不得写入入场时间");
                assertFalse(staleBuy, testCase.name() + ": 晚于边界的补发BUY必须为0");
            }
        }
    }

    @Test
    @DisplayName("归一化_处理模式与恢复时刻契约fail-fast")
    void normalize_processingModeContract() {
        StockReplayRequest baseline = StockReplayRunner.normalize(new StockReplayRequest(
                T0, T0, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), "target/replay-unit/mode-contract"));
        assertEquals(StockReplayProcessingModeEnum.ONLINE_BASELINE, baseline.processingMode(),
                "未显式指定模式必须归一化为ONLINE_BASELINE");
        assertNull(baseline.recoveredAt(), "未指定模式时recoveredAt必须为空");

        StockReplayRequest baselineWithRecoveredAt = invalidModeRequest(
                StockReplayProcessingModeEnum.ONLINE_BASELINE, T0.plusMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> StockReplayRunner.normalize(baselineWithRecoveredAt),
                "ONLINE_BASELINE携带recoveredAt必须fail-fast");
        StockReplayRequest stressWithoutRecoveredAt = invalidModeRequest(
                StockReplayProcessingModeEnum.RESTART_STRESS, null);
        assertThrows(IllegalArgumentException.class, () -> StockReplayRunner.normalize(stressWithoutRecoveredAt),
                "RESTART_STRESS缺失recoveredAt必须fail-fast");
        StockReplayRequest stressWithEarlyRecovery = invalidModeRequest(
                StockReplayProcessingModeEnum.RESTART_STRESS, T0.minusMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> StockReplayRunner.normalize(stressWithEarlyRecovery),
                "recoveredAt早于窗口起点必须fail-fast");
    }

    /**
     * 构建违反处理模式与恢复时刻契约的非法请求(固定模式契约测试维度)。
     *
     * @param mode        处理模式
     * @param recoveredAt 恢复时刻
     * @return 非法请求
     */
    private StockReplayRequest invalidModeRequest(StockReplayProcessingModeEnum mode, LocalDateTime recoveredAt) {
        return new StockReplayRequest(
                T0, T0, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), "target/replay-unit/mode-contract",
                mode, recoveredAt);
    }

    @Test
    @DisplayName("runId_处理模式与恢复时刻参与计算_不同语义不占用同一产物目录")
    void runId_processingModeAndRecoveredAtParticipate() {
        StockReplayRequest baseline = StockReplayRunner.normalize(new StockReplayRequest(
                T0, T0, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), "target/replay-unit/runid-mode"));
        StockReplayRequest stress = StockReplayRunner.normalize(new StockReplayRequest(
                T0, T0, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), "target/replay-unit/runid-mode",
                StockReplayProcessingModeEnum.RESTART_STRESS, T0.plusMinutes(30)));

        assertNotEquals(StockReplayRunner.computeRunId(baseline), StockReplayRunner.computeRunId(stress),
                "处理模式不同runId必须不同,禁止占用同一产物目录");
    }

    private static final int STALE_STOCK = 999;

    /**
     * 构建含STALE_STOCK单轮bar的窗口数据,用于入场过期边界回放测试。
     *
     * @param start 轮次时间
     * @return 窗口数据
     */
    private StockReplayWindowData staleBoundaryWindowData(LocalDateTime start) {
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock = new HashMap<>();
        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock = new HashMap<>();
        barsByStock.computeIfAbsent(STALE_STOCK, k -> new TreeMap<>())
                .put(start, bar(STALE_STOCK, start, new BigDecimal("100.00")));

        LocalDate month = start.toLocalDate().withDayOfMonth(1);
        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthly = new LinkedHashMap<>();
        monthly.put(month, Map.of(STALE_STOCK, monthlyState(STALE_STOCK, month, "M2_PROVISIONAL")));
        return new StockReplayWindowData(barsByStock, featuresByStock, monthly, null);
    }

    /**
     * 向回放引擎注入一个ENTRY_PENDING正式批次(占用1号槽位),用于验证入场过期边界。
     *
     * @param engine    回放引擎
     * @param staleAt   入场过期时间
     * @param roundTime 本轮时间
     * @return 注入的批次
     */
    private TornStockVirtualBatchDO seedEntryPendingAtStaleAt(StockReplayEngine engine,
                                                              LocalDateTime staleAt,
                                                              LocalDateTime roundTime) {
        try {
            java.lang.reflect.Field portfolioField = StockReplayEngine.class.getDeclaredField("portfolio");
            portfolioField.setAccessible(true);
            StockReplayPortfolio portfolio = (StockReplayPortfolio) portfolioField.get(engine);

            TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
            batch.setBatchNo("S" + STALE_STOCK);
            batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
            batch.setStocksId(STALE_STOCK);
            batch.setStocksShortname("T" + STALE_STOCK);
            batch.setPrimaryStrategy("RANGE");
            batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
            batch.setSignalReferencePrice(new BigDecimal("100.00"));
            batch.setSignalTime(roundTime.minusMinutes(15));
            batch.setExpectedEntryBarTime(roundTime);
            batch.setEntryStaleAt(staleAt);
            batch.setSlotId(1L);
            batch.setSlotNo(1);
            batch.setResetObserved(false);

            portfolio.addBatch(batch);
            TornStockPortfolioSlotDO slot = portfolio.slots().getFirst();
            slot.setReservedCash(new BigDecimal("2000000000.00"));
            slot.setAvailableCash(BigDecimal.ZERO);
            slot.setCurrentBatchId(batch.getId());
            slot.setSlotStatus(StockSlotStatusEnum.RESERVED.getCode());
            return batch;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("无法注入回放批次", e);
        }
    }

    private StockReplayWindowData dataInsufficientWindowData(LocalDateTime start) {
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock = new HashMap<>();
        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock = new HashMap<>();
        for (int round = 0; round < 4; round++) {
            LocalDateTime t = start.plusMinutes(round * Stock15mBarBuildService.BUCKET_MINUTES);
            barsByStock.computeIfAbsent(1, k -> new TreeMap<>()).put(t, bar(1, t, new BigDecimal("95.2")));
        }
        TornStockStrategyFeature15mDO dataInsufficientFeature = feature(1, start, new BigDecimal("95.2"));
        dataInsufficientFeature.setReturn7d(null);
        featuresByStock.computeIfAbsent(1, k -> new TreeMap<>()).put(start, dataInsufficientFeature);

        LocalDate month = start.toLocalDate().withDayOfMonth(1);
        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthly = new LinkedHashMap<>();
        monthly.put(month, Map.of(1, monthlyState(1, month, "M2_PROVISIONAL")));
        return new StockReplayWindowData(barsByStock, featuresByStock, monthly, null);
    }

    private StockReplayWindowData guardFailWindowData(LocalDateTime start) {
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock = new HashMap<>();
        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock = new HashMap<>();
        barsByStock.computeIfAbsent(1, k -> new TreeMap<>()).put(start, bar(1, start, new BigDecimal("95.2")));
        TornStockStrategyFeature15mDO guardFailFeature = feature(1, start, new BigDecimal("95.2"));
        guardFailFeature.setReturn7d(new BigDecimal("-0.03"));
        featuresByStock.computeIfAbsent(1, k -> new TreeMap<>()).put(start, guardFailFeature);

        LocalDate month = start.toLocalDate().withDayOfMonth(1);
        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthly = new LinkedHashMap<>();
        monthly.put(month, Map.of(1, monthlyState(1, month, "M2_PROVISIONAL")));
        return new StockReplayWindowData(barsByStock, featuresByStock, monthly, null);
    }

    private StockReplayEngine runEngine(StockReplayTrackEnum track) {
        StockReplayContext context = buildContext(track.name());
        StockReplayEngine engine = new StockReplayEngine(track, track.name(), context);
        engine.run();
        return engine;
    }

    private StockReplayContext buildContext(String runId) {
        LocalDateTime start = T0;
        LocalDateTime end = T0.plusMinutes((long) (ROUNDS - 1) * Stock15mBarBuildService.BUCKET_MINUTES);
        StockReplayRequest request = new StockReplayRequest(
                start, end, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.values()), "target/replay-unit/" + runId);
        return new StockReplayContext(request, buildWindowData(start));
    }

    private StockReplayWindowData buildWindowData(LocalDateTime start) {
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock = new HashMap<>();
        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock = new HashMap<>();
        LocalDate month = start.toLocalDate().withDayOfMonth(1);

        for (int round = 0; round < ROUNDS; round++) {
            LocalDateTime t = start.plusMinutes((long) round * Stock15mBarBuildService.BUCKET_MINUTES);
            BigDecimal price = PRICES[round];
            for (int stock = 1; stock <= MATCH_STOCKS; stock++) {
                barsByStock.computeIfAbsent(stock, k -> new TreeMap<>()).put(t, bar(stock, t, price));
                featuresByStock.computeIfAbsent(stock, k -> new TreeMap<>()).put(t, feature(stock, t, price));
            }
            barsByStock.computeIfAbsent(IMMATURE_STOCK, k -> new TreeMap<>()).put(t, bar(IMMATURE_STOCK, t, price));
            featuresByStock.computeIfAbsent(IMMATURE_STOCK, k -> new TreeMap<>())
                    .put(t, feature(IMMATURE_STOCK, t, price));
        }

        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthly = new LinkedHashMap<>();
        Map<Integer, TornStockMonthlyStateDO> byStock = new HashMap<>();
        for (int stock = 1; stock <= MATCH_STOCKS; stock++) {
            byStock.put(stock, monthlyState(stock, month, "M2_PROVISIONAL"));
        }
        byStock.put(IMMATURE_STOCK, monthlyState(IMMATURE_STOCK, month, "M1_EARLY"));
        monthly.put(month, byStock);

        return new StockReplayWindowData(barsByStock, featuresByStock, monthly, null);
    }

    private TornStockMarketBar15mDO bar(int stock, LocalDateTime t, BigDecimal price) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stock);
        bar.setStocksShortname("T" + stock);
        bar.setBarStartTime(t);
        bar.setBarEndTime(t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
        bar.setFirstSampleTime(t.plusMinutes(1));
        bar.setLastSampleTime(t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES - 1));
        bar.setSampleCount(10);
        bar.setUsable(true);
        bar.setLastPrice(price);
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }

    private TornStockStrategyFeature15mDO feature(int stock, LocalDateTime t, BigDecimal price) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(stock);
        feature.setStocksShortname("T" + stock);
        feature.setBarStartTime(t);
        feature.setReferencePrice(price);
        feature.setMa7d(new BigDecimal("96.0"));
        feature.setMa30d(new BigDecimal("96.0"));
        feature.setZscore1d(new BigDecimal("-1.0"));
        feature.setReturn6h(new BigDecimal("-0.005"));
        feature.setReturn7d(new BigDecimal("-0.02"));
        feature.setLow30d(new BigDecimal("95.0"));
        feature.setHigh30d(new BigDecimal("100.0"));
        feature.setWidth30d(new BigDecimal("0.05"));
        feature.setPosition30(price.subtract(new BigDecimal("95.0"))
                .divide(new BigDecimal("5.0"), 18, java.math.RoundingMode.HALF_UP));
        feature.setStrategyReady(true);
        feature.setFeatureVersion("1.0.0");
        return feature;
    }

    private TornStockMonthlyStateDO monthlyState(int stock, LocalDate month, String maturity) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stock);
        state.setStocksShortname("T" + stock);
        state.setEffectiveMonth(month);
        state.setStrategyFitPrior("NARROW");
        state.setMaturity(maturity);
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality("NARROW");
        state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
        state.setConfirmedAt(T0);
        state.setEvidenceStartTime(T0.minusDays(120));
        state.setEvidenceEndTime(T0);
        state.setPersonalityRuleVersion("PERSONALITY_RULE_V1");
        state.setRiskRuleVersion("RISK_RULE_V1_SHADOW");
        return state;
    }

    /**
     * 卖出费率(0.999)。
     */
    private static final BigDecimal FEE = new BigDecimal("0.999");
    /**
     * 批次编号时间格式。
     */
    private static final java.time.format.DateTimeFormatter REPLAY_TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm");
}
