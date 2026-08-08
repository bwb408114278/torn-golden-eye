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
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 股票月度风格状态初始化服务单元测试 - 覆盖当月草稿初始化、冻结公式委托、幂等与人工/系统确认流程
 * <p>
 * 验证 {@link StockMonthlyStateInitService} 的核心规则:
 * <ul>
 *   <li>当月已全部有任意有效状态时跳过初始化,返回0(幂等保护)</li>
 *   <li>无有效状态股票通过冻结计算器生成DRAFT草稿,规则版本为冻结字符串</li>
 *   <li>无可用bar证据时保持DRAFT且strategyFitPrior/riskLevel为空(禁止默认STEADY/NONE)</li>
 *   <li>{@code confirmDraftStates} 人工确认拒绝空白与SYSTEM</li>
 *   <li>{@code autoConfirmDraftStates} 仅确认满足自动确认条件的DRAFT</li>
 * </ul>
 * 通过 Mockito mock 全部DAO,使用 ArgumentCaptor 验证持久化字段。
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

    // ==================== initCurrentMonth ====================

    @Test
    @DisplayName("月度初始化_ 当月全部股票已有任意有效状态,跳过初始化返回0")
    void initCurrentMonth_allStocksExisting_skipAndReturnZero() {
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
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of(1, 2));
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of());

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(0, result, "当月已存在DRAFT有效状态时应返回0,不重复INSERT");
        verify(monthlyStateDao, never()).insertDraftStatesIgnoreConflict(any());
    }

    @Test
    @DisplayName("月度初始化_ 无任意有效状态,无可用bar时生成证据不完整DRAFT草稿")
    void initCurrentMonth_noExistingRecords_createsIncompleteDraftForEachStock() {
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"), buildStock(2, "MSG"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of());
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of());
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of());
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(2, result, "应为2支股票创建草稿");
        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        List<TornStockMonthlyStateDO> saved = monthlyStatesCaptor.getValue();
        assertEquals(2, saved.size(), "应保存2条草稿记录");

        for (TornStockMonthlyStateDO state : saved) {
            assertEquals(currentMonth, state.getEffectiveMonth(), "生效月份应为当月1日");
            assertEquals(StockMonthlyStateStatusEnum.DRAFT.getCode(), state.getStateStatus(),
                    "状态应为DRAFT");
            assertNull(state.getStrategyFitPrior(),
                    "证据不完整时strategyFitPrior应为null,禁止默认STEADY");
            assertNull(state.getRiskLevel(),
                    "证据不完整时riskLevel应为null,禁止默认NONE");
            assertEquals(StockMaturityEnum.M0_UNMATURE.getCode(), state.getMaturity(),
                    "无证据时成熟度应为M0_UNMATURE");
            assertEquals(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION,
                    state.getPersonalityRuleVersion(), "风格规则版本应为冻结版本");
            assertEquals(StockMonthlyStateCalculator.RISK_RULE_VERSION,
                    state.getRiskRuleVersion(), "风险规则版本应为冻结版本");
            assertNotNull(state.getCalculatedAt(), "calculatedAt不应为null");
            assertNull(state.getConfirmedAt(), "草稿态confirmedAt应为null");
            assertNull(state.getConfirmedBy(), "草稿态confirmedBy应为null");
            assertNotNull(state.getMetricSnapshot(), "metricSnapshot不应为null");
            assertTrue(state.getMetricSnapshot().contains("MONTHLY_EVIDENCE_INCOMPLETE"),
                    "metricSnapshot应记录证据不完整原因");
        }
    }

    @Test
    @DisplayName("月度初始化_ 混合DRAFT/CONFIRMED/缺失,只为缺失股票插入且不覆盖已有DRAFT")
    void initCurrentMonth_mixedExistingDraftConfirmed_missingOnlyInserted() {
        List<TornStocksDO> allStocks = List.of(
                buildStock(1, "TCS"), buildStock(2, "MSG"), buildStock(3, "JUN"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of(1, 2));
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of(buildConfirmedState(2, "MSG", currentMonth)));
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of());
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any())).thenReturn(List.of());
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
    }

    @Test
    @DisplayName("月度初始化_ 查询后并发冲突被数据库DO NOTHING吸收,返回实际插入数")
    void initCurrentMonth_concurrentConflictIgnored_returnsActualInsertedCount() {
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"), buildStock(3, "JUN"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth))
                .thenReturn(List.of());
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth))
                .thenReturn(List.of());
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of());
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenReturn(1);

        int result = monthlyStateInitService.initCurrentMonth();

        assertEquals(1, result, "应返回实际插入数1,不抛重复键异常");
        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(any());
    }

    // ==================== confirmDraftStates ====================

    @Test
    @DisplayName("确认草稿状态_ 确认人为空抛异常")
    void confirmDraftStates_blankConfirmedBy_throws() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        assertThrows(IllegalArgumentException.class,
                () -> monthlyStateInitService.confirmDraftStates(effectiveMonth, "  "));
    }

    @Test
    @DisplayName("确认草稿状态_ 确认人为SYSTEM抛异常")
    void confirmDraftStates_systemConfirmedBy_throws() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        assertThrows(IllegalArgumentException.class,
                () -> monthlyStateInitService.confirmDraftStates(effectiveMonth, "SYSTEM"));
    }

    @Test
    @DisplayName("确认草稿状态_ 当月存在DRAFT记录,批量转为CONFIRMED并返回确认数")
    void confirmDraftStates_draftRecordsExist_batchConfirmed() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        TornStockMonthlyStateDO draft1 = buildCompleteDraftState(1, "TCS", effectiveMonth);
        TornStockMonthlyStateDO draft2 = buildCompleteDraftState(2, "MSG", effectiveMonth);
        List<TornStockMonthlyStateDO> drafts = List.of(draft1, draft2);

        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(drafts);

        int result = monthlyStateInitService.confirmDraftStates(effectiveMonth, "Bai");

        assertEquals(2, result, "应确认2条记录");
        verify(monthlyStateDao).updateBatchById(monthlyStatesCaptor.capture());
        List<TornStockMonthlyStateDO> updated = monthlyStatesCaptor.getValue();
        assertEquals(2, updated.size(), "应更新2条记录");
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

    // ==================== autoConfirmDraftStates ====================

    @Test
    @DisplayName("自动确认_ 满足自动确认条件的DRAFT批量确认且确认人为SYSTEM")
    void autoConfirmDraftStates_confirmableDrafts_confirmedAsSystem() {
        LocalDate effectiveMonth = LocalDate.of(2026, 7, 1);
        TornStockMonthlyStateDO draft = buildAutoConfirmableDraft(1, "TCS", effectiveMonth);
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(effectiveMonth))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), eq(StockMonthlyStateStatusEnum.DRAFT.getCode()))).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));

        int result = monthlyStateInitService.autoConfirmDraftStates(effectiveMonth);

        assertEquals(1, result, "应自动确认1条记录");
        verify(monthlyStateDao).updateBatchById(monthlyStatesCaptor.capture());
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
        verify(monthlyStateDao, never()).updateBatchById(any());
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
        verify(monthlyStateDao, never()).updateBatchById(any());
    }

    // ==================== recalculateCurrentMonthDrafts ====================

    @Test
    @DisplayName("月度重算_当月无未确认非人工覆盖DRAFT_跳过返回0")
    void recalculateCurrentMonthDrafts_noDraft_returnsZero() {
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of());

        int result = monthlyStateInitService.recalculateCurrentMonthDrafts();

        assertEquals(0, result, "无DRAFT时应返回0");
        verify(monthlyStateDao, never()).recalculateDraftStates(any());
    }

    @Test
    @DisplayName("月度重算_已有完整普通DRAFT_重算更新指标并保留主键")
    void recalculateCurrentMonthDrafts_existingDraft_recalculatedWithEvidence() {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        TornStockMonthlyStateDO draft = buildDraftState(1, "TCS", currentMonth);
        draft.setId(99L);
        draft.setManualOverride(false);

        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));
        when(tornStocksDao.listByIds(any())).thenReturn(List.of(buildStock(1, "TCS")));
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of());
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.recalculateDraftStates(any())).thenReturn(1);

        int result = monthlyStateInitService.recalculateCurrentMonthDrafts();

        assertEquals(1, result, "应重算并更新1条DRAFT");
        ArgumentCaptor<List<TornStockMonthlyStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(monthlyStateDao).recalculateDraftStates(captor.capture());
        List<TornStockMonthlyStateDO> updated = captor.getValue();
        assertEquals(1, updated.size(), "应重算1条");
        assertEquals(99L, updated.getFirst().getId(), "重算必须保留原主键");
        assertEquals(StockMonthlyStateStatusEnum.DRAFT.getCode(), updated.getFirst().getStateStatus(),
                "重算后仍为DRAFT");
    }

    @Test
    @DisplayName("月度重算_人工覆盖DRAFT不进入重算候选")
    void recalculateCurrentMonthDrafts_manualOverrideDraft_excludedFromCandidates() {
        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of());

        int result = monthlyStateInitService.recalculateCurrentMonthDrafts();

        assertEquals(0, result);
        verify(monthlyStateDao, never()).recalculateDraftStates(any());
        // 人工覆盖记录由查询谓词(manualOverride=false)排除,服务不主动构建其重算
    }

    @Test
    @DisplayName("月度重算_股票不存在时跳过该DRAFT不阻塞其余")
    void recalculateCurrentMonthDrafts_missingStock_skipsThatDraft() {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        TornStockMonthlyStateDO draft = buildDraftState(1, "TCS", currentMonth);
        draft.setId(99L);
        draft.setManualOverride(false);

        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));
        when(tornStocksDao.listByIds(any())).thenReturn(List.of());

        int result = monthlyStateInitService.recalculateCurrentMonthDrafts();

        assertEquals(0, result, "股票不存在应跳过且返回0");
        verify(monthlyStateDao, never()).recalculateDraftStates(any());
    }

    @Test
    @DisplayName("月度重算_证据补齐后空DRAFT升级为非空机器建议")
    void recalculateCurrentMonthDrafts_emptyDraftWithEvidence_becomesNonEmpty() {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        TornStockMonthlyStateDO draft = buildDraftState(1, "TCS", currentMonth);
        draft.setId(99L);
        draft.setManualOverride(false);

        LocalDateTime evidenceEnd = currentMonth.atStartOfDay().minusMinutes(15);
        List<TornStockMarketBar15mDO> denseBars = buildDenseEvidenceBars(evidenceEnd.minusDays(10), evidenceEnd);

        when(monthlyStateDao.lambdaQuery()).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.eq(any(), any())).thenReturn(monthlyStateQuery);
        when(monthlyStateQuery.list()).thenReturn(List.of(draft));
        when(tornStocksDao.listByIds(any())).thenReturn(List.of(buildStock(1, "TCS")));
        // 证据窗口完整: 首尾bar与窗口内10天每15分钟bar齐全,满足95%覆盖率与10个日收盘
        TornStockMarketBar15mDO edge = new TornStockMarketBar15mDO();
        edge.setStocksId(1);
        edge.setFirstSampleTime(evidenceEnd.minusDays(10));
        edge.setBarEndTime(evidenceEnd);
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of(edge));
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(denseBars);
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.recalculateDraftStates(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });

        monthlyStateInitService.recalculateCurrentMonthDrafts();

        ArgumentCaptor<List<TornStockMonthlyStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(monthlyStateDao).recalculateDraftStates(captor.capture());
        TornStockMonthlyStateDO updated = captor.getValue().getFirst();
        assertNotNull(updated.getStrategyFitPrior(), "证据补齐后应得到非空机器建议");
        assertNotNull(updated.getEvidenceStartTime(), "证据起点应写入");
        assertNotNull(updated.getEvidenceEndTime(), "证据终点应写入");
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
    @DisplayName("月度初始化_ 末日23:45末桶_证据终点取桶闭合时间而非bar_start_time")
    void initCurrentMonth_2345LastBucket_evidenceEndIsBarEndTime() {
        List<TornStocksDO> allStocks = List.of(buildStock(1, "TCS"));
        when(tornStocksDao.list()).thenReturn(allStocks);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        when(monthlyStateDao.selectExistingStockIdsByMonth(currentMonth)).thenReturn(List.of());
        when(monthlyStateDao.selectConfirmedByMonth(currentMonth)).thenReturn(List.of());

        TornStockMarketBar15mDO edge = new TornStockMarketBar15mDO();
        edge.setStocksId(1);
        edge.setFirstSampleTime(LocalDateTime.of(2025, 8, 1, 0, 0));
        edge.setBarEndTime(LocalDateTime.of(2026, 7, 31, 23, 45).plusMinutes(15));
        when(bar15mDao.selectUsableEvidenceEdges(any(), any(), any())).thenReturn(List.of(edge));
        when(bar15mDao.selectUsableByStocksAndTimeRange(any(), any(), any(), any())).thenReturn(List.of());
        when(monthlyStateDao.selectPreviousConfirmedByStocks(any(), any())).thenReturn(List.of());
        when(monthlyStateDao.insertDraftStatesIgnoreConflict(any())).thenAnswer(inv -> {
            List<TornStockMonthlyStateDO> states = inv.getArgument(0);
            return states.size();
        });

        monthlyStateInitService.initCurrentMonth();

        verify(monthlyStateDao).insertDraftStatesIgnoreConflict(monthlyStatesCaptor.capture());
        TornStockMonthlyStateDO saved = monthlyStatesCaptor.getValue().getFirst();
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), saved.getEvidenceEndTime(),
                "末日23:45末桶的证据终点必须取桶闭合时间(次日00:00),否则最近完整月被排除");
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
     * 构建满足自动确认条件的DRAFT(冻结版本、完整、无人工覆盖)。
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
        state.setMetricSnapshot("{\"rawPersonality\":\"STEADY\"}");
        state.setManualOverride(false);
        return state;
    }
}
