package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalResult.SignalEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.StockCandidateTrackAllocationService.CandidateAcceptanceTarget;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双 Shadow 候选接纳端到端真实PostgreSQL集成测试。
 * <p>
 * 覆盖第三轮 Review 11.4: 同 SHADOW round 的 6 个 ALLOWED 候选在真实数据库下得到
 * 6 event、5 candidate、6 unlimited, 第 6 个候选影子槽位满时记录
 * {@code NO_AVAILABLE_SLOT}, 其事件仅无 shadowCandidateBatchId、不丢 shadowBatchId。
 * 并证明候选插入/事件写入任一失败时, 事件与两类批次在单个事务边界内全部回滚。
 * <p>
 * 使用 {@link TransactionTemplate} 显式控制事务边界(等价于
 * {@code StockRoundTransactionService.executeRound} 的 {@code @Transactional} 语义),
 * 候选影子槽位在内存快照中构造, 不触碰共享库真实 VIP 槽位; 事件与批次经真实 DAO 落库,
 * {@code @AfterEach} 按隔离股票集合精确物理 DELETE, 不删除任何业务数据。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.11
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("双Shadow候选接纳端到端真实PostgreSQL集成测试")
class StockShadowTrackEndToEndItTest {

    @Autowired
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Autowired
    private TornStockSignalEventDAO signalEventDao;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 隔离股票ID起点(6个候选使用2099701..2099706, 远离生产股票与其它测试命名空间)
     */
    private static final int STOCK_BASE = 2099701;
    /**
     * 隔离轮次时间(未来, 远离生产数据)
     */
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2099, 12, 2, 10, 0);
    /**
     * 隔离股票短名前缀(总长不超过8字符)
     */
    private static final String SHORTNAME_PREFIX = "T";
    /**
     * 候选影子槽位ID起点(内存构造, 不落库)
     */
    private static final long SLOT_ID_BASE = 209970001L;

    @AfterEach
    void cleanupIsolatedData() {
        List<Integer> stocks = IntStream.rangeClosed(0, 5).mapToObj(i -> STOCK_BASE + i).toList();
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_virtual_batch WHERE stocks_id IN (:stocks)",
                Map.of("stocks", stocks));
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_signal_event WHERE stocks_id IN (:stocks)",
                Map.of("stocks", stocks));
    }

    @Test
    @DisplayName("真实PG_同轮6候选_5槽候选影子6事件6无限资金_第6名NO_AVAILABLE_SLOT仅无候选批次ID")
    void sixCandidates_fiveCandidateSlots_sixUnlimitedSixEventsSixthNoSlot() {
        List<Integer> stocks = IntStream.rangeClosed(0, 5).mapToObj(i -> STOCK_BASE + i).toList();
        List<CandidateInfo> candidates = stocks.stream()
                .map(s -> new CandidateInfo(s, SHORTNAME_PREFIX + s, StockBuyStrategyEnum.RANGE_LOWER_BUY,
                        List.of(StockBuyStrategyEnum.RANGE_LOWER_BUY.getCode()), BigDecimal.ONE))
                .toList();
        Map<Integer, TornStockMarketBar15mDO> barByStock = buildBars(stocks);
        Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock = buildMonthlyStates(stocks);
        Map<Integer, SignalEvaluation> evaluationByStockId = buildEvaluations(stocks);
        RoundSnapshot snapshot = buildSnapshot();

        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(signalEventDao, virtualBatchDao);
        StockCandidateTrackAllocationService allocationService = new StockCandidateTrackAllocationService(
                virtualBatchDao, new StockPortfolioService(), recorder);

        transactionTemplate.executeWithoutResult(status -> {
            StockCandidateAllocationResult allocation = allocationService.acceptCandidates(
                    candidates, snapshot, barByStock, monthlyStateByStock, evaluationByStockId, ROUND_TIME,
                    CandidateAcceptanceTarget.candidateShadow());

            assertEquals(5, allocation.allocatedBatches().size(), "候选影子应仅接纳前5名");
            assertEquals(StockCandidateAllocationResultEnum.NO_AVAILABLE_SLOT,
                    allocation.resultByStockId().get(STOCK_BASE + 5), "第6名必须记录NO_AVAILABLE_SLOT");

            Map<Integer, Integer> rankByStockId = buildRankByStockId(candidates);
            recorder.writeShadowRecords(
                    new ArrayList<>(evaluationByStockId.values()), List.of(),
                    allocation.allocatedBatches(), rankByStockId,
                    allocation.resultByStockId(), ROUND_TIME);
        });

        assertEquals(6, countEvents(stocks), "6个候选必须产生6个事件");
        assertEquals(5, countBatches(stocks, StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode()),
                "候选影子槽位批次必须为5");
        assertEquals(6, countBatches(stocks, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode()),
                "无限资金影子批次必须为6");

        List<TornStockSignalEventDO> events = selectEvents(stocks);
        TornStockSignalEventDO sixth = events.stream()
                .filter(e -> STOCK_BASE + 5 == e.getStocksId())
                .findFirst().orElseThrow();
        assertEquals("SHADOW", sixth.getPortfolioDecision(), "第6名组合决策必须为SHADOW");
        assertNull(sixth.getShadowCandidateBatchId(), "第6名不得有候选影子批次ID");
        assertTrue(sixth.getShadowBatchId() != null, "第6名必须保留无限资金影子批次ID");
    }

    @Test
    @DisplayName("真实PG_同一轮重复执行_事件与三类批次数量保持不变_幂等收敛")
    void sameRoundReExecuted_eventAndBatchCountsUnchanged_idempotent() {
        List<Integer> stocks = IntStream.rangeClosed(0, 5).mapToObj(i -> STOCK_BASE + i).toList();
        List<CandidateInfo> candidates = stocks.stream()
                .map(s -> new CandidateInfo(s, SHORTNAME_PREFIX + s, StockBuyStrategyEnum.RANGE_LOWER_BUY,
                        List.of(StockBuyStrategyEnum.RANGE_LOWER_BUY.getCode()), BigDecimal.ONE))
                .toList();
        Map<Integer, TornStockMarketBar15mDO> barByStock = buildBars(stocks);
        Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock = buildMonthlyStates(stocks);
        Map<Integer, SignalEvaluation> evaluationByStockId = buildEvaluations(stocks);
        RoundSnapshot snapshot = buildSnapshot();

        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(signalEventDao, virtualBatchDao);
        StockCandidateTrackAllocationService allocationService = new StockCandidateTrackAllocationService(
                virtualBatchDao, new StockPortfolioService(), recorder);

        transactionTemplate.executeWithoutResult(status -> executeAllocationChain(
                candidates, snapshot, barByStock, monthlyStateByStock, evaluationByStockId,
                recorder, allocationService));
        transactionTemplate.executeWithoutResult(status -> executeAllocationChain(
                candidates, snapshot, barByStock, monthlyStateByStock, evaluationByStockId,
                recorder, allocationService));

        assertEquals(6, countEvents(stocks), "幂等重复执行后事件数量必须保持6");
        assertEquals(5, countBatches(stocks, StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode()),
                "幂等重复执行后候选影子批次必须保持5");
        assertEquals(6, countBatches(stocks, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode()),
                "幂等重复执行后无限资金影子批次必须保持6");
    }

    @Test
    @DisplayName("真实PG_候选写入后后续步骤失败_事件与两类批次在同一事务内全部回滚")
    void candidateWriteFailure_eventAndBothLedgerBatchesAllRolledBack() {
        List<Integer> stocks = IntStream.rangeClosed(0, 5).mapToObj(i -> STOCK_BASE + i).toList();
        List<CandidateInfo> candidates = stocks.stream()
                .map(s -> new CandidateInfo(s, SHORTNAME_PREFIX + s, StockBuyStrategyEnum.RANGE_LOWER_BUY,
                        List.of(StockBuyStrategyEnum.RANGE_LOWER_BUY.getCode()), BigDecimal.ONE))
                .toList();
        Map<Integer, TornStockMarketBar15mDO> barByStock = buildBars(stocks);
        Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock = buildMonthlyStates(stocks);
        Map<Integer, SignalEvaluation> evaluationByStockId = buildEvaluations(stocks);
        RoundSnapshot snapshot = buildSnapshot();

        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(signalEventDao, virtualBatchDao);
        StockCandidateTrackAllocationService allocationService = new StockCandidateTrackAllocationService(
                virtualBatchDao, new StockPortfolioService(), recorder);

        // 成功写入后人为抛出异常, 模拟 executeRound 中候选接纳之后(无限批次保存/事件回写/槽位预留)任一步骤失败;
        // 整个事务必须回滚, 不得残留任何事件或两类批次。
        assertThrows(IllegalStateException.class, () ->
                        transactionTemplate.executeWithoutResult(status -> {
                            executeAllocationChain(candidates, snapshot, barByStock, monthlyStateByStock,
                                    evaluationByStockId, recorder, allocationService);
                            throw new IllegalStateException("模拟候选接纳后后续步骤失败");
                        }),
                "事务内任一后续步骤失败必须抛异常以触发整轮回滚");

        assertEquals(0, countEvents(stocks), "事务回滚后事件必须为0");
        assertEquals(0, countBatches(stocks, StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode()),
                "事务回滚后候选影子批次必须为0");
        assertEquals(0, countBatches(stocks, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode()),
                "事务回滚后无限资金影子批次必须为0");
    }

    /**
     * 执行一次完整的候选接纳 + 影子记录写入链路(与 executeRound 中 SHADOW 模式步骤等价)。
     *
     * @param candidates            候选列表
     * @param snapshot              轮次快照
     * @param barByStock            bar映射
     * @param monthlyStateByStock   月度状态映射
     * @param evaluationByStockId   信号评估映射
     * @param recorder              影子记录器
     * @param allocationService     候选接纳服务
     */
    private void executeAllocationChain(List<CandidateInfo> candidates,
                                        RoundSnapshot snapshot,
                                        Map<Integer, TornStockMarketBar15mDO> barByStock,
                                        Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock,
                                        Map<Integer, SignalEvaluation> evaluationByStockId,
                                        StockShadowTrackRecorder recorder,
                                        StockCandidateTrackAllocationService allocationService) {
        StockCandidateAllocationResult allocation = allocationService.acceptCandidates(
                candidates, snapshot, barByStock, monthlyStateByStock, evaluationByStockId, ROUND_TIME,
                CandidateAcceptanceTarget.candidateShadow());
        Map<Integer, Integer> rankByStockId = buildRankByStockId(candidates);
        recorder.writeShadowRecords(
                new ArrayList<>(evaluationByStockId.values()), List.of(),
                allocation.allocatedBatches(), rankByStockId,
                allocation.resultByStockId(), ROUND_TIME);
    }

    /**
     * 构建包含候选影子槽位的轮次快照(槽位在内存构造, 不落库)。
     * 固定构造5个候选影子槽位与5个正式槽位, 使6候选场景中第6名无候选槽位可用。
     *
     * @return 轮次快照
     */
    private RoundSnapshot buildSnapshot() {
        List<TornStockPortfolioSlotDO> candidateSlots = IntStream.rangeClosed(1, 5)
                .mapToObj(slotNo -> candidateSlot(SLOT_ID_BASE + slotNo, slotNo))
                .toList();
        List<TornStockPortfolioSlotDO> formalSlots = IntStream.rangeClosed(1, 5)
                .mapToObj(slotNo -> candidateSlot(SLOT_ID_BASE + 100 + slotNo, slotNo))
                .peek(slot -> slot.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .toList();
        List<TornStockPortfolioSlotDO> merged = new ArrayList<>(formalSlots);
        merged.addAll(candidateSlots);
        return new RoundSnapshot(List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), merged, ROUND_TIME);
    }

    /**
     * 构建候选影子槽位(内存对象, 状态AVAILABLE、现金20亿)。
     *
     * @param id     槽位ID
     * @param slotNo 槽位序号
     * @return 候选影子槽位
     */
    private TornStockPortfolioSlotDO candidateSlot(Long id, int slotNo) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE);
        slot.setSlotNo(slotNo);
        slot.setInitialCash(StockPortfolioService.INITIAL_CASH);
        slot.setAvailableCash(StockPortfolioService.INITIAL_CASH);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        return slot;
    }

    /**
     * 为隔离股票构建可用bar(价格有效供候选接纳计算)。
     *
     * @param stocks 股票ID集合
     * @return bar映射
     */
    private Map<Integer, TornStockMarketBar15mDO> buildBars(List<Integer> stocks) {
        Map<Integer, TornStockMarketBar15mDO> map = new HashMap<>();
        for (Integer stock : stocks) {
            TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
            bar.setStocksId(stock);
            bar.setStocksShortname(SHORTNAME_PREFIX + stock);
            bar.setBarStartTime(ROUND_TIME);
            bar.setBarEndTime(ROUND_TIME.plusMinutes(15));
            bar.setLastPrice(new BigDecimal("100.00"));
            map.put(stock, bar);
        }
        return map;
    }

    /**
     * 为隔离股票构建月度状态(CONFIRMED、风格RANGING、成熟度M2、风险NONE)。
     *
     * @param stocks 股票ID集合
     * @return 月度状态映射
     */
    private Map<Integer, TornStockMonthlyStateDO> buildMonthlyStates(List<Integer> stocks) {
        Map<Integer, TornStockMonthlyStateDO> map = new HashMap<>();
        for (Integer stock : stocks) {
            TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
            state.setStocksId(stock);
            state.setStocksShortname(SHORTNAME_PREFIX + stock);
            state.setEffectiveMonth(ROUND_TIME.toLocalDate().withDayOfMonth(1));
            state.setStrategyFitPrior(StockStrategyFitEnum.RANGING.getCode());
            state.setMaturity(StockMaturityEnum.M2_PROVISIONAL.getCode());
            state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
            state.setSuggestedPersonality(StockStrategyFitEnum.RANGING.getCode());
            state.setManualOverride(false);
            state.setMetricSnapshot("{}");
            state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
            map.put(stock, state);
        }
        return map;
    }

    /**
     * 为隔离股票构建信号评估(边沿触发、ALLOWED、未入选正式)。
     *
     * @param stocks 股票ID集合
     * @return 信号评估映射
     */
    private Map<Integer, SignalEvaluation> buildEvaluations(List<Integer> stocks) {
        StockBuyStrategy strategy = new RangeLowerBuyStrategy();
        Map<Integer, SignalEvaluation> map = new HashMap<>();
        for (Integer stock : stocks) {
            BuyContext context = new BuyContext(
                    stock, SHORTNAME_PREFIX + stock, new BigDecimal("100.00"),
                    new BigDecimal("100.0"), new BigDecimal("100.0"), new BigDecimal("100.0"),
                    new BigDecimal("-1.0"), new BigDecimal("-0.5"), new BigDecimal("-0.3"),
                    new BigDecimal("-0.005"), new BigDecimal("-0.01"), new BigDecimal("-0.02"),
                    new BigDecimal("-0.03"), new BigDecimal("98.00"), new BigDecimal("102.00"),
                    new BigDecimal("0.04"), new BigDecimal("0.05"),
                    new BigDecimal("0.020408"), new BigDecimal("0.019608"),
                    true, StockStrategyFitEnum.RANGING, StockMaturityEnum.M2_PROVISIONAL,
                    StockRiskLevelEnum.NONE);
            SignalEvaluation evaluation = SignalEvaluation.builder(stock, SHORTNAME_PREFIX + stock)
                    .evaluatedStrategies(List.of(strategy))
                    .primaryStrategy(strategy)
                    .matchedStrategies(List.of(strategy))
                    .qualityScore(BigDecimal.ONE)
                    .edgeTriggered(true)
                    .context(context)
                    .monthlyState(buildMonthlyStates(List.of(stock)).get(stock))
                    .eligibilityResult(new EligibilityResult(StockEligibilityResultEnum.ALLOWED, List.of()))
                    .acceptedFormal(false)
                    .build();
            map.put(stock, evaluation);
        }
        return map;
    }

    /**
     * 构建候选排名映射(stocksId -> 1起始排名)。
     *
     * @param candidates 候选列表
     * @return 排名映射
     */
    private Map<Integer, Integer> buildRankByStockId(List<CandidateInfo> candidates) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            map.putIfAbsent(candidates.get(i).stocksId(), i + 1);
        }
        return map;
    }

    /**
     * 统计指定股票集合的事件行数。
     *
     * @param stocks 股票ID集合
     * @return 事件行数
     */
    private int countEvents(List<Integer> stocks) {
        return namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torn_stock_signal_event WHERE stocks_id IN (:stocks) AND deleted = 0",
                Map.of("stocks", stocks), Integer.class);
    }

    /**
     * 统计指定股票集合与账本类型的批次行数。
     *
     * @param stocks     股票ID集合
     * @param ledgerType 账本类型
     * @return 批次行数
     */
    private int countBatches(List<Integer> stocks, String ledgerType) {
        return namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torn_stock_virtual_batch "
                        + "WHERE stocks_id IN (:stocks) AND ledger_type = :ledger AND deleted = 0",
                Map.of("stocks", stocks, "ledger", ledgerType), Integer.class);
    }

    /**
     * 查询指定股票集合的事件列表(按股票ID升序)。
     *
     * @param stocks 股票ID集合
     * @return 事件列表
     */
    private List<TornStockSignalEventDO> selectEvents(List<Integer> stocks) {
        return namedJdbcTemplate.query(
                "SELECT * FROM torn_stock_signal_event WHERE stocks_id IN (:stocks) AND deleted = 0 ORDER BY stocks_id",
                Map.of("stocks", stocks),
                (rs, rowNum) -> {
                    TornStockSignalEventDO event = new TornStockSignalEventDO();
                    event.setId(rs.getLong("id"));
                    event.setEventNo(rs.getString("event_no"));
                    event.setRoundTime(rs.getTimestamp("round_time").toLocalDateTime());
                    event.setStocksId(rs.getInt("stocks_id"));
                    event.setStocksShortname(rs.getString("stocks_shortname"));
                    event.setStrategyType(rs.getString("strategy_type"));
                    event.setPortfolioDecision(rs.getString("portfolio_decision"));
                    event.setFormalBatchId(toLongOrNull(rs.getObject("formal_batch_id")));
                    event.setShadowBatchId(toLongOrNull(rs.getObject("shadow_batch_id")));
                    event.setShadowCandidateBatchId(toLongOrNull(rs.getObject("shadow_candidate_batch_id")));
                    return event;
                });
    }

    /**
     * 将数据库值安全转换为Long(避免空值转换异常)。
     *
     * @param value 数据库对象值
     * @return Long值;为空时返回null
     */
    private Long toLongOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }
}
