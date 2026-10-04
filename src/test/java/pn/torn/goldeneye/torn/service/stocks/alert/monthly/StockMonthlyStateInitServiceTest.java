package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 股票月度风格状态初始化服务单元测试 - 覆盖启动编排草稿生成、冻结公式委托与系统自动确认流程
 * <p>
 * 验证 {@link StockMonthlyStateInitService} 的核心规则:
 * <ul>
 *   <li>{@code autoConfirmDraftStates} 仅确认满足自动确认条件(含快照confirmable=true)的DRAFT</li>
 *   <li>{@code refreshCurrentMonthStates} 编排: 分片载入证据,一次完成初始化/重算/自动确认,
 *       证据终点取末桶闭合时间(次日00:00)</li>
 *   <li>迟滞前态SQL携带当前双版本精确过滤,首月previous=null,仅取同版本CONFIRMED</li>
 * </ul>
 * 通过 Mockito mock 全部DAO,使用 ArgumentCaptor 验证持久化字段。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票月度风格状态初始化服务测试")
class StockMonthlyStateInitServiceTest {

    @Mock
    private TornStocksDAO tornStocksDao;
    @Mock
    private TornStockMonthlyStateDAO monthlyStateDao;
    @Mock
    private TornStockMarketBar15mDAO bar15mDao;
    @Mock
    private LambdaQueryChainWrapper<TornStockMonthlyStateDO> monthlyStateQuery;
    @Mock
    private StockMarketClock marketClock;
    @Captor
    private ArgumentCaptor<List<TornStockMonthlyStateDO>> monthlyStatesCaptor;

    @InjectMocks
    private StockMonthlyStateInitService monthlyStateInitService;

    @BeforeEach
    void setUp() {
        lenient().when(marketClock.today()).thenReturn(LocalDate.now());
        lenient().when(marketClock.now()).thenReturn(LocalDateTime.now());
        // 计算器为无状态纯类,使用真实实例以保证冻结公式路径
        org.springframework.test.util.ReflectionTestUtils.setField(
                monthlyStateInitService, "calculator", new StockMonthlyStateCalculator());
    }

    // ==================== autoConfirmDraftStates ====================
    // 1.8.0复活精简版只保留SYSTEM自动确认,人工确认入口confirmDraftStates不取回,对应用例一并删除

    @Test
    @DisplayName("自动确认_ 满足自动确认条件的DRAFT批量确认且确认人为SYSTEM")
    void autoConfirmDraftStates_confirmableDrafts_confirmedAsSystem() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        TornStockMonthlyStateDO draft = buildAutoConfirmableDraft(1, "TCS", effectiveMonth);
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));
        when(monthlyStateDao.autoConfirmDraftStates(anyList())).thenReturn(1);

        int result = monthlyStateInitService.autoConfirmDraftStates(effectiveMonth);

        assertEquals(1, result, "应自动确认1条记录并返回实际受影响行数");
        verify(monthlyStateDao).autoConfirmDraftStates(monthlyStatesCaptor.capture());
        TornStockMonthlyStateDO updated = monthlyStatesCaptor.getValue().getFirst();
        assertEquals(StockMonthlyStateStatusEnum.CONFIRMED.getCode(), updated.getStateStatus());
        assertEquals("SYSTEM", updated.getConfirmedBy(), "自动确认人应为SYSTEM");
        assertNotNull(updated.getConfirmedAt(), "confirmedAt不应为null");
    }

    @Test
    @DisplayName("自动确认_ 人工覆盖草稿不自动确认")
    void autoConfirmDraftStates_manualOverriddenDraft_notConfirmed() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        TornStockMonthlyStateDO draft = buildAutoConfirmableDraft(1, "TCS", effectiveMonth);
        draft.setManualOverride(true);
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));

        int result = monthlyStateInitService.autoConfirmDraftStates(effectiveMonth);

        assertEquals(0, result, "人工覆盖草稿不得自动确认");
        verify(monthlyStateDao, never()).autoConfirmDraftStates(any());
    }

    @Test
    @DisplayName("自动确认_ 版本不匹配草稿不自动确认")
    void autoConfirmDraftStates_oldRuleVersion_notConfirmed() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        TornStockMonthlyStateDO draft = buildAutoConfirmableDraft(1, "TCS", effectiveMonth);
        draft.setPersonalityRuleVersion("1.0.0");
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));

        int result = monthlyStateInitService.autoConfirmDraftStates(effectiveMonth);

        assertEquals(0, result, "旧规则版本草稿不得自动确认");
        verify(monthlyStateDao, never()).autoConfirmDraftStates(any());
    }

    @Test
    @DisplayName("自动确认_ 完整但快照confirmable=false的DRAFT不自动确认(fail-closed)")
    void autoConfirmDraftStates_snapshotNotConfirmable_notConfirmed() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        TornStockMonthlyStateDO draft = buildAutoConfirmableDraft(1, "TCS", effectiveMonth);
        draft.setMetricSnapshot("{\"rawPersonality\":\"STEADY\",\"confirmable\":false}");
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));

        int result = monthlyStateInitService.autoConfirmDraftStates(effectiveMonth);

        assertEquals(0, result, "完整但confirmable=false的DRAFT不得自动确认");
        verify(monthlyStateDao, never()).autoConfirmDraftStates(any());
    }

    // ==================== refreshCurrentMonthStates ====================

    @Test
    @DisplayName("月度编排_一次完成初始化插入/重算更新/自动确认且顺序固定")
    void refreshCurrentMonthStates_insertRecalculateConfirmInOrder() {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        // 股票1当月已有未确认DRAFT(重算),股票2当月缺失(初始化)
        TornStockMonthlyStateDO draft = buildDraftState(1, "TCS", currentMonth);
        draft.setId(99L);
        draft.setManualOverride(false);
        when(tornStocksDao.list()).thenReturn(List.of(buildStock(1, "TCS"), buildStock(2, "MSG")));
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth)).thenReturn(List.of(1));
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        // 第一次list为编排内的DRAFT候选查询,第二次为自动确认查询(返回confirmable=true快照)
        when(monthlyStateQuery.list()).thenReturn(List.of(draft))
                .thenReturn(List.of(buildAutoConfirmableDraft(1, "TCS", currentMonth)));
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of());
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });
        when(monthlyStateDao.recalculateDraftStates(any())).thenReturn(1);
        when(monthlyStateDao.autoConfirmDraftStates(anyList())).thenReturn(1);

        StockMonthlyStateInitService.MonthlyRefreshResult result =
                monthlyStateInitService.refreshCurrentMonthStates();

        assertEquals(1, result.insertedCount(), "应为缺失的2号股票初始化插入1条");
        assertEquals(1, result.recalculatedCount(), "应重算更新1条已有DRAFT");
        assertEquals(1, result.confirmedCount(), "应自动确认1条");
        InOrder inOrder = inOrder(monthlyStateDao);
        inOrder.verify(monthlyStateDao).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        inOrder.verify(monthlyStateDao).recalculateDraftStates(any());
        inOrder.verify(monthlyStateDao).autoConfirmDraftStates(anyList());
        assertEquals(Integer.valueOf(2), monthlyStatesCaptor.getValue().getFirst().getStocksId(),
                "插入候选应只含当月缺失的2号股票");
    }

    @Test
    @DisplayName("月度编排_证据按批分片载入且全部草稿共用同一calculatedAt")
    void refreshCurrentMonthStates_shardsEvidenceAndSharesCalculatedAt() {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        List<TornStocksDO> stocks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            stocks.add(buildStock(i, "S" + i));
        }
        when(tornStocksDao.list()).thenReturn(stocks);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth)).thenReturn(List.of());
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of());
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of());
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });
        LocalDateTime fixedNow = LocalDateTime.of(2026, 10, 1, 8, 0);
        lenient().when(marketClock.now()).thenReturn(fixedNow);

        StockMonthlyStateInitService.MonthlyRefreshResult result =
                monthlyStateInitService.refreshCurrentMonthStates();

        assertEquals(10, result.insertedCount(), "10支全部缺失股票应插入10条草稿");
        // 10支股票按批大小8分2片载入证据(而非整表一次载入)
        ArgumentCaptor<List<Integer>> shardCaptor = ArgumentCaptor.forClass(List.class);
        verify(bar15mDao, times(2)).selectUsableEvidenceEdges(shardCaptor.capture(), any(), any());
        List<List<Integer>> shards = shardCaptor.getAllValues();
        assertEquals(8, shards.get(0).size(), "第一片应为8支股票");
        assertEquals(2, shards.get(1).size(), "第二片应为剩余2支股票");
        verify(bar15mDao, times(2)).selectUsableByStocksAndTimeRange(any(), any(), any(), any());
        verify(monthlyStateDao, times(2)).selectPreviousConfirmedByStocks(any(), any(), any(), any());
        // 插入只累积执行一次,且全部草稿共用同一calculatedAt
        verify(monthlyStateDao, times(1)).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        List<TornStockMonthlyStateDO> inserted = monthlyStatesCaptor.getValue();
        assertEquals(10, inserted.size(), "应一次批量插入10条草稿");
        inserted.forEach(state -> assertEquals(fixedNow, state.getCalculatedAt(),
                "跨批次全部草稿必须共用同一calculatedAt"));
    }

    /**
     * 构建10天内每15分钟一个bar的密集证据窗口(满足月度证据95%覆盖率与10个日收盘要求)。
     *
     * @param start 证据起点
     * @param end   证据终点(含)
     * @return 密集bar列表
     */
    private List<TornStockMarketBar15mDO> buildDenseEvidenceBars(LocalDateTime start, LocalDateTime end) {
        List<TornStockMarketBar15mDO> bars = new java.util.ArrayList<>();
        BigDecimal price = new BigDecimal("100.00");
        for (LocalDateTime t = start; !t.isAfter(end); t = t.plusMinutes(15)) {
            TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
            bar.setStocksId(1);
            bar.setBarStartTime(t);
            bar.setBarEndTime(t.plusMinutes(15));
            bar.setLastPrice(price);
            bar.setUsable(true);
            bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
            bars.add(bar);
        }
        return bars;
    }

    @Test
    @DisplayName("月度编排_末日23:45末桶_证据终点取桶闭合时间而非bar_start_time")
    void refreshCurrentMonthStates_2345LastBucket_evidenceEndIsBarEndTime() {
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth)).thenReturn(List.of());
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        // 编排候选查询与自动确认查询均无DRAFT: 该股当月缺失,走初始化插入路径
        when(monthlyStateQuery.list()).thenReturn(List.of());

        TornStockMarketBar15mDO edge = new TornStockMarketBar15mDO();
        edge.setStocksId(1);
        edge.setFirstSampleTime(LocalDateTime.of(2025, 8, 1, 0, 0));
        edge.setBarEndTime(LocalDateTime.of(2026, 7, 31, 23, 45).plusMinutes(15));
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of(edge));
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });

        monthlyStateInitService.refreshCurrentMonthStates();

        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        TornStockMonthlyStateDO saved = monthlyStatesCaptor.getValue().getFirst();
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), saved.getEvidenceEndTime(),
                "末日23:45末桶的证据终点必须取桶闭合时间(次日00:00),否则最近完整月被排除");
    }

    // ==================== 严格版本隔离迟滞 ====================

    @Test
    @DisplayName("迟滞版本隔离_2026-03首月_SQL必须携带当前双版本且previous=null")
    void loadPrevious_strictVersionIsolation_v2FirstMonthNullPrevious() {
        LocalDate targetMonth = LocalDate.of(2026, 3, 1);
        TornStockMonthlyStateDO draft = buildDraftState(1, "TCS", targetMonth);
        draft.setId(99L);
        draft.setManualOverride(false);
        // 编排月份取自时钟:固定为2026-03,使证据与迟滞查询命中目标月
        when(marketClock.today()).thenReturn(LocalDate.of(2026, 3, 15));

        when(tornStocksDao.list()).thenReturn(List.of(buildStock(1, "TCS")));
        when(monthlyStateDao.selectExistingStockIdsByMonth(targetMonth)).thenReturn(List.of());
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of());
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        // 数据库只有2026-02旧版本CONFIRMED: 当前双版本过滤下必须返回空(首月previous=null)
        when(monthlyStateDao.selectPreviousConfirmedByStocks(
                List.of(1), targetMonth,
                StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION,
                StockMonthlyStateCalculator.RISK_RULE_VERSION))
                .thenReturn(List.of());
        when(monthlyStateDao.recalculateDraftStates(any())).thenReturn(1);

        monthlyStateInitService.refreshCurrentMonthStates();

        // 严格断言: SQL侧必须携带当前双版本精确过滤,禁止读到旧版本后再Java回退
        verify(monthlyStateDao).selectPreviousConfirmedByStocks(
                any(), eq(targetMonth),
                eq(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION),
                eq(StockMonthlyStateCalculator.RISK_RULE_VERSION));
        ArgumentCaptor<List<TornStockMonthlyStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(monthlyStateDao).recalculateDraftStates(captor.capture());
        assertNull(captor.getValue().getFirst().getPreviousPersonality(),
                "首月无更早同版本CONFIRMED时previous必须为null");
    }

    @Test
    @DisplayName("迟滞版本隔离_2026-04_仅使用2026-03的同版本CONFIRMED作为迟滞前态")
    void loadPrevious_strictVersionIsolation_aprilUsesMarchConfirmed() {
        LocalDate targetMonth = LocalDate.of(2026, 4, 1);
        TornStockMonthlyStateDO marchConfirmed = buildConfirmedState(1, "TCS",
                LocalDate.of(2026, 3, 1));
        marchConfirmed.setPersonalityRuleVersion(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION);
        marchConfirmed.setRiskRuleVersion(StockMonthlyStateCalculator.RISK_RULE_VERSION);
        TornStockMonthlyStateDO draft = buildDraftState(1, "TCS", targetMonth);
        draft.setId(99L);
        draft.setManualOverride(false);
        LocalDateTime evidenceEnd = targetMonth.atStartOfDay().minusMinutes(15);
        List<TornStockMarketBar15mDO> denseBars = buildDenseEvidenceBars(
                evidenceEnd.minusDays(30), evidenceEnd);
        // 编排月份取自时钟:固定为2026-04,使证据与迟滞查询命中目标月
        when(marketClock.today()).thenReturn(LocalDate.of(2026, 4, 15));

        when(tornStocksDao.list()).thenReturn(List.of(buildStock(1, "TCS")));
        when(monthlyStateDao.selectExistingStockIdsByMonth(targetMonth)).thenReturn(List.of());
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));
        TornStockMarketBar15mDO edge = new TornStockMarketBar15mDO();
        edge.setStocksId(1);
        edge.setFirstSampleTime(evidenceEnd.minusDays(30));
        edge.setBarEndTime(evidenceEnd);
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of(edge));
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(denseBars);
        when(monthlyStateDao.selectPreviousConfirmedByStocks(
                List.of(1), targetMonth,
                StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION,
                StockMonthlyStateCalculator.RISK_RULE_VERSION))
                .thenReturn(List.of(marchConfirmed));
        when(monthlyStateDao.recalculateDraftStates(any())).thenReturn(1);

        monthlyStateInitService.refreshCurrentMonthStates();

        ArgumentCaptor<List<TornStockMonthlyStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(monthlyStateDao).recalculateDraftStates(captor.capture());
        TornStockMonthlyStateDO updated = captor.getValue().getFirst();
        assertEquals("STEADY", updated.getPreviousPersonality(),
                "2026-04迟滞前态必须来自2026-03同版本CONFIRMED");
        assertEquals(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION,
                updated.getPersonalityRuleVersion(), "重算草稿必须写当前冻结风格版本");
        assertEquals(StockMonthlyStateCalculator.RISK_RULE_VERSION,
                updated.getRiskRuleVersion(), "重算草稿必须写当前冻结风险版本");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建标准股票DO。
     *
     * @param id        股票ID
     * @param shortname 股票简称
     * @return 股票DO
     */
    private TornStocksDO buildStock(int id, String shortname) {
        TornStocksDO stock = new TornStocksDO();
        stock.setId(id);
        stock.setStocksName(shortname + "_NAME");
        stock.setStocksShortname(shortname);
        stock.setCurrentPrice(new java.math.BigDecimal("100.00"));
        return stock;
    }

    /**
     * 构建CONFIRMED状态的月度状态DO。
     *
     * @param stocksId       股票ID
     * @param shortname      股票简称
     * @param effectiveMonth 生效月份
     * @return CONFIRMED状态DO
     */
    private TornStockMonthlyStateDO buildConfirmedState(int stocksId, String shortname,
                                                        LocalDate effectiveMonth) {
        TornStockMonthlyStateDO state = buildDraftState(stocksId, shortname, effectiveMonth);
        state.setStrategyFitPrior("STEADY");
        state.setMaturity(StockMaturityEnum.M4_MATURE.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
        state.setConfirmedAt(LocalDateTime.now());
        state.setConfirmedBy("SYSTEM");
        return state;
    }

    /**
     * 构建DRAFT状态的月度状态DO。
     *
     * @param stocksId       股票ID
     * @param shortname      股票简称
     * @param effectiveMonth 生效月份
     * @return DRAFT状态DO
     */
    private TornStockMonthlyStateDO buildDraftState(int stocksId, String shortname,
                                                    LocalDate effectiveMonth) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setId((long) stocksId);
        state.setStocksId(stocksId);
        state.setStocksShortname(shortname);
        state.setEffectiveMonth(effectiveMonth);
        state.setStateStatus(StockMonthlyStateStatusEnum.DRAFT.getCode());
        state.setCalculatedAt(LocalDateTime.now());
        return state;
    }

    /**
     * 构建满足人工确认完整性的DRAFT。
     *
     * @param stocksId       股票ID
     * @param shortname      股票简称
     * @param effectiveMonth 生效月份
     * @return 完整DRAFT
     */
    private TornStockMonthlyStateDO buildCompleteDraftState(int stocksId, String shortname,
                                                            LocalDate effectiveMonth) {
        TornStockMonthlyStateDO state = buildDraftState(stocksId, shortname, effectiveMonth);
        state.setStrategyFitPrior("STEADY");
        state.setMaturity(StockMaturityEnum.M4_MATURE.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality("STEADY");
        state.setEvidenceStartTime(LocalDateTime.of(2025, 1, 1, 0, 0));
        state.setEvidenceEndTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return state;
    }

    /**
     * 构建满足自动确认条件的DRAFT(冻结版本、完整、无人工覆盖、快照confirmable=true)。
     *
     * @param stocksId       股票ID
     * @param shortname      股票简称
     * @param effectiveMonth 生效月份
     * @return 自动可确认DRAFT
     */
    private TornStockMonthlyStateDO buildAutoConfirmableDraft(int stocksId, String shortname,
                                                              LocalDate effectiveMonth) {
        TornStockMonthlyStateDO state = buildCompleteDraftState(stocksId, shortname, effectiveMonth);
        state.setPersonalityRuleVersion(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION);
        state.setRiskRuleVersion(StockMonthlyStateCalculator.RISK_RULE_VERSION);
        state.setMetricSnapshot("{\"rawPersonality\":\"STEADY\",\"confirmable\":true}");
        state.setManualOverride(false);
        return state;
    }
}
