package pn.torn.goldeneye.torn.service.stocks.alert;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 股票月度风格状态初始化服务单元测试 - 覆盖当月草稿初始化、风格映射fail-closed与草稿确认流程
 * <p>
 * 验证 {@link StockMonthlyStateInitService} 的核心规则:
 * <ul>
 *   <li>当月已全部CONFIRMED时跳过初始化,返回0(幂等保护)</li>
 *   <li>无CONFIRMED记录时为每支未确认股票创建DRAFT草稿,批量保存</li>
 *   <li>风格配置缺失时stylePrior=null(fail-closed,禁止默认STEADY)</li>
 *   <li>{@code confirmDraftStates} 将指定月份DRAFT记录批量转为CONFIRMED</li>
 * </ul>
 * 通过 Mockito mock 全部DAO与 {@link SysSettingManager},使用 ArgumentCaptor 验证持久化字段。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.25
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
    private SysSettingManager sysSettingManager;
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
        lenient().when(marketClock.now()).thenReturn(java.time.LocalDateTime.now());
    }

    // ==================== initCurrentMonth ====================

    @Test
    @DisplayName("月度初始化_ 当月全部股票已有任意有效状态,跳过初始化返回0")
    void initCurrentMonth_allStocksExisting_skipAndReturnZero() {
        // 2支股票,均已在当月存在任意有效状态
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"), buildStock(2, "MSG"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of(1, 2));
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of(
                        buildConfirmedState(1, "TCS", currentMonth),
                        buildConfirmedState(2, "MSG", currentMonth)
                ));

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(0, result, "全部已存在有效状态时应返回0");
        verify(monthlyStateDao, never()).insertDraftStatesIgnoreConflict(any());
    }

    @Test
    @DisplayName("月度初始化_ 当月全部股票已有DRAFT有效状态,跳过初始化返回0且不覆盖")
    void initCurrentMonth_allStocksHaveDraft_skipAndReturnZero() {
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"), buildStock(2, "MSG"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        // 当月已有DRAFT有效状态(非CONFIRMED)
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of(1, 2));
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of());

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(0, result, "当月已存在DRAFT有效状态时应返回0,不重复INSERT");
        verify(monthlyStateDao, never()).insertDraftStatesIgnoreConflict(any());
    }

    @Test
    @DisplayName("月度初始化_ 无任意有效状态,为每支股票创建DRAFT草稿")
    void initCurrentMonth_noExistingRecords_createsDraftForEachStock() {
        // 2支股票,均无任意有效状态
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"), buildStock(2, "MSG"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of());
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of());
        // 风格配置: TCS -> STEADY, MSG -> STRONG
        when(sysSettingManager.getStockPersonalities())
                .thenReturn(Map.of(
                        "TCS", StockPersonalityEnum.STEADY,
                        "MSG", StockPersonalityEnum.STRONG
                ));
        // 无证据bar -> 成熟度M0_UNMATURE
        when(bar15mDao.selectEvidenceRanges(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(2, result, "应为2支股票创建草稿");
        // 验证批量保存的草稿字段
        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        List<TornStockMonthlyStateDO> saved = monthlyStatesCaptor.getValue();
        assertEquals(2, saved.size(), "应保存2条草稿记录");

        // 验证每条草稿字段填充正确
        for (TornStockMonthlyStateDO state : saved) {
            assertEquals(currentMonth, state.getEffectiveMonth(), "生效月份应为当月1日");
            assertEquals(StockMonthlyStateStatusEnum.DRAFT.getCode(), state.getStateStatus(),
                    "状态应为DRAFT");
            assertEquals(StockRiskLevelEnum.NONE.getCode(), state.getRiskLevel(),
                    "风险等级应统一为NONE");
            assertEquals(StockMaturityEnum.M0_UNMATURE.getCode(), state.getMaturity(),
                    "bar数为0时成熟度应为M0_UNMATURE");
            assertNotNull(state.getCalculatedAt(), "calculatedAt不应为null");
            assertNull(state.getConfirmedAt(), "草稿态confirmedAt应为null");
            assertNull(state.getConfirmedBy(), "草稿态confirmedBy应为null");
            assertNotNull(state.getMetricSnapshot(), "metricSnapshot不应为null");
        }
        // 验证风格映射正确:TCS -> STEADY, MSG -> STRONG
        TornStockMonthlyStateDO tcsState = saved.stream()
                .filter(s -> Integer.valueOf(1).equals(s.getStocksId()))
                .findFirst()
                .orElseThrow();
        assertEquals("STEADY", tcsState.getStrategyFitPrior(), "TCS风格应为STEADY");
        TornStockMonthlyStateDO msgState = saved.stream()
                .filter(s -> Integer.valueOf(2).equals(s.getStocksId()))
                .findFirst()
                .orElseThrow();
        assertEquals("STRONG", msgState.getStrategyFitPrior(), "MSG风格应为STRONG");
    }

    @Test
    @DisplayName("月度初始化_ 混合DRAFT/CONFIRMED/缺失,只为缺失股票插入且不覆盖已有DRAFT")
    void initCurrentMonth_mixedExistingDraftConfirmed_missingOnlyInserted() {
        // 3支股票: 1号已有DRAFT,2号已有CONFIRMED,3号缺失
        List<TornStocksDO> allStocks = List.of(
                buildStock(1, "TCS"), buildStock(2, "MSG"), buildStock(3, "JUN"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of(1, 2));
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of(buildConfirmedState(2, "MSG", currentMonth)));
        when(sysSettingManager.getStockPersonalities())
                .thenReturn(Map.of("JUN", StockPersonalityEnum.NARROW));
        when(bar15mDao.selectEvidenceRanges(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(1, result, "只为缺失的3号股票插入草稿");
        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        List<TornStockMonthlyStateDO> saved = monthlyStatesCaptor.getValue();
        assertEquals(1, saved.size(), "应只保存1条草稿记录");
        assertEquals(Integer.valueOf(3), saved.getFirst().getStocksId(), "应只插入3号股票");
        assertEquals("NARROW", saved.getFirst().getStrategyFitPrior(), "3号股票风格应为NARROW");
    }

    @Test
    @DisplayName("月度初始化_ 查询后并发冲突被数据库DO NOTHING吸收,返回实际插入数")
    void initCurrentMonth_concurrentConflictIgnored_returnsActualInsertedCount() {
        // 模拟: 应用查询时3号股票缺失,但INSERT前另一入口插入了同一键,冲突被DO NOTHING吸收
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"), buildStock(3, "JUN"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of());
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of());
        when(sysSettingManager.getStockPersonalities())
                .thenReturn(Map.of("TCS", StockPersonalityEnum.STEADY, "JUN", StockPersonalityEnum.NARROW));
        when(bar15mDao.selectEvidenceRanges(any(), any())).thenReturn(List.of());
        // 数据库实际只插入1行(3号被并发入口先插入,1号成功)
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenReturn(1);

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(1, result, "应返回实际插入数1,不抛重复键异常");
        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(any());
    }

    @Test
    @DisplayName("月度初始化_ 风格配置缺失,strategyFitPrior为null(fail-closed,禁止默认STEADY)")
    void initCurrentMonth_styleConfigMissing_strategyFitPriorIsNull() {
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of());
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of());
        // 风格配置为空Map(fail-closed)
        when(sysSettingManager.getStockPersonalities())
                .thenReturn(Map.of());
        when(bar15mDao.selectEvidenceRanges(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(1, result, "应为1支股票创建草稿");
        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        List<TornStockMonthlyStateDO> saved = monthlyStatesCaptor.getValue();
        assertEquals(1, saved.size(), "应保存1条草稿记录");

        TornStockMonthlyStateDO state = saved.getFirst();
        assertNull(state.getStrategyFitPrior(),
                "风格配置缺失时strategyFitPrior应为null(fail-closed),禁止默认STEADY");
        assertEquals(StockMonthlyStateStatusEnum.DRAFT.getCode(), state.getStateStatus(),
                "状态应为DRAFT");
        // metricSnapshot应记录styleSource为MISSING_FAIL_CLOSED
        assertNotNull(state.getMetricSnapshot(), "metricSnapshot不应为null");
        assertTrue(state.getMetricSnapshot().contains("MISSING_FAIL_CLOSED"),
                "metricSnapshot应记录fail-closed来源,实际: " + state.getMetricSnapshot());
    }

    // ==================== confirmDraftStates ====================

    @Test
    @DisplayName("确认草稿状态_ 当月存在DRAFT记录,批量转为CONFIRMED并返回确认数")
    void confirmDraftStates_draftRecordsExist_batchConfirmed() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        // 2条DRAFT记录待确认
        TornStockMonthlyStateDO draft1 = buildDraftState(1, "TCS", effectiveMonth);
        TornStockMonthlyStateDO draft2 = buildDraftState(2, "MSG", effectiveMonth);
        completeDraftState(draft1);
        completeDraftState(draft2);
        List<TornStockMonthlyStateDO> drafts = List.of(draft1, draft2);

        // mock lambdaQuery链式调用
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(drafts);

        int result = monthlyStateInitService.confirmDraftStates(effectiveMonth, "Bai");

        assertEquals(2, result, "应确认2条记录");
        // 验证updateBatchById被调用
        verify(monthlyStateDao).updateBatchById(monthlyStatesCaptor.capture());
        List<TornStockMonthlyStateDO> updated = monthlyStatesCaptor.getValue();
        assertEquals(2, updated.size(), "应更新2条记录");
        // 验证状态流转与确认字段填充
        for (TornStockMonthlyStateDO state : updated) {
            assertEquals(StockMonthlyStateStatusEnum.CONFIRMED.getCode(), state.getStateStatus(),
                    "状态应流转为CONFIRMED");
            assertNotNull(state.getConfirmedAt(), "confirmedAt不应为null");
            assertEquals("Bai", state.getConfirmedBy(), "确认人应为实际确认人");
        }
    }

    @Test
    @DisplayName("确认草稿状态_ 当月无DRAFT记录,返回0且不触发更新")
    void confirmDraftStates_noDraftRecords_returnZeroWithoutUpdate() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of());

        int result = monthlyStateInitService.confirmDraftStates(effectiveMonth, "Bai");

        assertEquals(0, result, "无DRAFT记录时应返回0");
        verify(monthlyStateDao, never()).updateBatchById(any());
    }

    @Test
    @DisplayName("确认草稿状态_不完整记录保留草稿且不更新数据库")
    void confirmDraftStates_incompleteDraft_keepsDraftWithoutUpdate() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        TornStockMonthlyStateDO draft = buildDraftState(1, "TCS", effectiveMonth);
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));

        int result = monthlyStateInitService.confirmDraftStates(effectiveMonth, "Bai");

        assertEquals(0, result);
        assertEquals(StockMonthlyStateStatusEnum.DRAFT.getCode(), draft.getStateStatus());
        verify(monthlyStateDao, never()).updateBatchById(any());
    }

    /**
     * 构建标准股票DO
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
     * 构建CONFIRMED状态的月度状态DO(用于mock已确认记录)
     *
     * @param stocksId       股票ID
     * @param shortname      股票简称
     * @param effectiveMonth 生效月份
     * @return CONFIRMED状态DO
     */
    private TornStockMonthlyStateDO buildConfirmedState(int stocksId, String shortname,
                                                        LocalDate effectiveMonth) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setStocksShortname(shortname);
        state.setEffectiveMonth(effectiveMonth);
        state.setStrategyFitPrior("STEADY");
        state.setMaturity(StockMaturityEnum.M4_MATURE.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
        state.setCalculatedAt(java.time.LocalDateTime.now());
        state.setConfirmedAt(java.time.LocalDateTime.now());
        state.setConfirmedBy("SYSTEM");
        return state;
    }

    /**
     * 构建DRAFT状态的月度状态DO(用于mock待确认记录)
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
        state.setStrategyFitPrior("STEADY");
        state.setMaturity(StockMaturityEnum.M0_UNMATURE.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setStateStatus(StockMonthlyStateStatusEnum.DRAFT.getCode());
        state.setCalculatedAt(java.time.LocalDateTime.now());
        return state;
    }

    private void completeDraftState(TornStockMonthlyStateDO state) {
        state.setSuggestedPersonality(state.getStrategyFitPrior());
        state.setEvidenceStartTime(java.time.LocalDateTime.of(2025, 1, 1, 0, 0));
        state.setEvidenceEndTime(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
