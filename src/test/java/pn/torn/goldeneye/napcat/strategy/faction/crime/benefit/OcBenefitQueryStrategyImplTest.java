package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitUserRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.crime.income.FactionOcExclusion;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;
import pn.torn.goldeneye.torn.service.faction.oc.income.TornOcIncomeService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OC收益查询策略编排测试。
 *
 * <p>验证策略入口确实构造带大锅饭排除规则的{@link OcBenefitRankingQuery}，
 * 并调用生产DAO的{@code queryPersonalBenefitList}。个人普通收益明细加载全部大锅饭帮派排除规则，
 * 由每条记录自身的帮派、OC名称和完成时间决定是否排除，不锁定当前帮派。本测试是入口接线证据，
 * 不能替代真实Mapper数据库测试，日期边界与排除结论以{@code TornFactionOcBenefitMapperTest}为准。</p>
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.04
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("OC收益查询策略编排测试")
class OcBenefitQueryStrategyImplTest {
    @Autowired
    private OcBenefitQueryStrategyImpl strategy;
    @MockitoSpyBean
    private TornFactionOcBenefitDAO benefitDao;
    @MockitoSpyBean
    private TornOcIncomeService incomeService;
    @Autowired
    private TornFactionOcIncomeSummaryDAO incomeSummaryDao;
    @Autowired
    private TornSettingFactionManager settingFactionManager;

    @Test
    @DisplayName("个人普通收益明细构造跨帮派排除规则查询并调用生产DAO")
    void queryBenefitList_buildsCrossFactionExclusionQuery() throws Exception {
        TornUserDO user = new TornUserDO();
        user.setId(8809001L);
        user.setFactionId(TornConstants.FACTION_NOV_ID);
        LocalDateTime from = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 31, 23, 59, 59);

        invokeQueryBenefitList(user, from, to);

        ArgumentCaptor<OcBenefitRankingQuery> queryCaptor = ArgumentCaptor.forClass(OcBenefitRankingQuery.class);
        verify(benefitDao).queryPersonalBenefitList(queryCaptor.capture());
        OcBenefitRankingQuery query = queryCaptor.getValue();
        assertNotNull(query);
        assertEquals(0L, query.getFactionId());
        assertEquals(8809001L, query.getUserId());
        assertEquals(from, query.getFromDate());
        assertEquals(to, query.getToDate());
        List<FactionOcExclusion> rules = query.getFactionOcExclusions();
        assertFalse(rules.isEmpty());
        // 规则覆盖全部大锅饭帮派，且包含带生效时间的NOV新增名单，证明不锁定当前帮派
        for (Long fid : TornConstants.REASSIGN_OC_FACTION) {
            assertTrue(rules.stream().anyMatch(r -> fid.equals(r.getFactionId())));
        }
        assertTrue(rules.stream().anyMatch(r -> r.getEffectiveFrom() != null
                && r.getOcList().contains(TornConstants.OC_NAME_LOCK_STOCK)));
    }

    @Test
    @DisplayName("普通帮派用户同样加载跨帮派排除规则，由记录自身帮派决定排除")
    void queryBenefitList_normalFaction_loadsCrossFactionRules() throws Exception {
        TornUserDO user = new TornUserDO();
        user.setId(8809002L);
        user.setFactionId(9999L);
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 31, 23, 59, 59);

        invokeQueryBenefitList(user, from, to);

        ArgumentCaptor<OcBenefitRankingQuery> queryCaptor = ArgumentCaptor.forClass(OcBenefitRankingQuery.class);
        verify(benefitDao).queryPersonalBenefitList(queryCaptor.capture());
        List<FactionOcExclusion> rules = queryCaptor.getValue().getFactionOcExclusions();
        assertFalse(rules.isEmpty());
        for (Long fid : TornConstants.REASSIGN_OC_FACTION) {
            assertTrue(rules.stream().anyMatch(r -> fid.equals(r.getFactionId())));
        }
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("同月更换帮派：个人summary跨帮派聚合，不抛TooManyResults")
    void queryIncomeSummary_sameMonthFactionChange_aggregatesCrossFaction() throws Exception {
        Long userId = 8809003L;
        insertSummary(userId, TornConstants.FACTION_PN_ID, "2026-08", 1000L);
        insertSummary(userId, TornConstants.FACTION_NOV_ID, "2026-08", 2000L);

        TornFactionOcIncomeSummaryDO combined = invokeQueryIncomeSummary(
                userId, LocalDateTime.of(2026, 8, 31, 23, 59, 59));

        assertNotNull(combined);
        assertEquals(userId, combined.getUserId());
        assertEquals("2026-08", combined.getYearMonth());
        assertEquals(3000L, combined.getTotalReward());
        assertEquals(3000L, combined.getFinalIncome());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("当前普通帮派用户不会在策略入口被跳过历史大锅饭income与summary")
    void queryOcData_ordinaryFactionUser_returnsHistoricalPotData() throws Exception {
        Long userId = 8809004L;
        // 历史大锅饭明细由生产收入服务按结算月份返回，即使当前用户已不在大锅饭帮派
        doReturn(List.of(buildIncome(userId))).when(incomeService)
                .queryUserIncomeBySettlementMonth(userId, "2026-08");
        // 历史大锅饭summary真实插入（PN 2026-08）
        insertSummary(userId, TornConstants.FACTION_PN_ID, "2026-08", 1000L);

        TornUserDO user = new TornUserDO();
        user.setId(userId);
        user.setFactionId(9999L);
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 31, 23, 59, 59);

        Object result = invokeQueryOcData(user, from, to);

        List<?> incomeList = (List<?>) result.getClass().getMethod("getIncomeList").invoke(result);
        Object summary = result.getClass().getMethod("getIncomeSummary").invoke(result);
        assertFalse(incomeList.isEmpty());
        assertNotNull(summary);
    }

    @Test
    @DisplayName("未来月份参数回复格式介绍，不触发用户解析与查询")
    void handle_futureMonth_returnsFormatIntro() {
        List<? extends QqMsgParam<?>> result = strategy.handle(0L, new QqRecMsgSender(), "8809001#2099-01");

        assertEquals(1, result.size());
        assertInstanceOf(TextQqMsg.class, result.getFirst());
        assertEquals("参数有误，正确格式：g#OC收益(#用户ID)(#yyyy-MM)，月份不得晚于当月",
                ((TextQqMsg) result.getFirst()).getData().text());
        verify(benefitDao, never()).queryPersonalBenefitList(any());
    }

    @Test
    @DisplayName("历史月构建整月查询范围：月初零点到月末23:59:59")
    void buildDateRange_historyMonth_coversWholeMonth() throws Exception {
        Object dateRange = invokeBuildDateRange(YearMonth.of(2026, 7));

        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0, 0), readRangeField(dateRange, "fromDate"));
        assertEquals(LocalDateTime.of(2026, 7, 31, 23, 59, 59), readRangeField(dateRange, "toDate"));
    }

    @Test
    @DisplayName("排名按查询月份构造并展示年月文案：归属帮派不同于当前帮派时展示帮派简称")
    void buildUserRankingMsg_historyMonthFactionMismatch_showsRecordFaction() {
        TornUserDO user = new TornUserDO();
        user.setId(8809005L);
        user.setNickname("测试用户");
        user.setFactionId(9999L);
        TornFactionOcBenefitUserRankDO ranking = buildRanking(TornConstants.FACTION_PN_ID);
        doReturn(ranking).when(benefitDao).queryBenefitUserRanking(any());

        String msg = strategy.buildUserRankingMsg(user, YearMonth.of(2026, 7));

        String factionShortName = settingFactionManager.getIdMap()
                .get(TornConstants.FACTION_PN_ID).getFactionShortName();
        assertTrue(msg.contains("2026年7月"));
        assertTrue(msg.contains("在" + factionShortName + "中排名第3"));
        assertFalse(msg.contains("本帮"));

        ArgumentCaptor<OcBenefitRankingQuery> queryCaptor = ArgumentCaptor.forClass(OcBenefitRankingQuery.class);
        verify(benefitDao).queryBenefitUserRanking(queryCaptor.capture());
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0, 0), queryCaptor.getValue().getFromDate());
    }

    @Test
    @DisplayName("归属帮派与当前帮派一致时保持本帮文案")
    void buildUserRankingMsg_factionMatch_keepsOwnFactionCopy() {
        TornUserDO user = new TornUserDO();
        user.setId(8809006L);
        user.setNickname("测试用户");
        user.setFactionId(TornConstants.FACTION_PN_ID);
        doReturn(buildRanking(TornConstants.FACTION_PN_ID)).when(benefitDao).queryBenefitUserRanking(any());

        String msg = strategy.buildUserRankingMsg(user, YearMonth.now());

        assertTrue(msg.contains("在本帮中排名第3"));
        assertTrue(msg.contains(YearMonth.now().getMonthValue() + "月的OC中赚了"));
    }

    /**
     * 通过反射调用私有方法{@code buildDateRange(YearMonth)}。
     *
     * @param month 查询年月
     * @return 策略内部私有{@code DateRange}
     * @throws Exception 反射异常
     */
    private Object invokeBuildDateRange(YearMonth month) throws Exception {
        Method method = strategy.getClass().getDeclaredMethod("buildDateRange", YearMonth.class);
        method.setAccessible(true);
        return method.invoke(strategy, month);
    }

    /**
     * 通过反射读取私有record{@code DateRange}的时间字段。
     *
     * @param dateRange 月份范围对象
     * @param fieldName record组件名
     * @return 字段值
     * @throws Exception 反射异常
     */
    private LocalDateTime readRangeField(Object dateRange, String fieldName) throws Exception {
        Method accessor = dateRange.getClass().getDeclaredMethod(fieldName);
        accessor.setAccessible(true);
        return (LocalDateTime) accessor.invoke(dateRange);
    }

    /**
     * 构造一条测试用排名结果。
     *
     * @param factionId 收益归属帮派ID
     * @return 排名结果
     */
    private TornFactionOcBenefitUserRankDO buildRanking(Long factionId) {
        TornFactionOcBenefitUserRankDO ranking = new TornFactionOcBenefitUserRankDO();
        ranking.setFactionId(factionId);
        ranking.setBenefit(1000L);
        ranking.setItemCost(100L);
        ranking.setFactionRank(3L);
        ranking.setCohortRank(5L);
        ranking.setCohortUsers(45L);
        ranking.setOverallRank(8L);
        return ranking;
    }

    /**
     * 通过反射调用私有方法{@code queryBenefitList(TornUserDO, DateRange)}，
     * 其中{@code DateRange}为策略内部私有record。
     *
     * @param user 用户
     * @param from 查询开始时间
     * @param to   查询结束时间
     * @throws Exception 反射异常
     */
    private void invokeQueryBenefitList(TornUserDO user, LocalDateTime from, LocalDateTime to) throws Exception {
        Class<?> strategyClass = strategy.getClass();
        Class<?> dateRangeClass = Class.forName(
                "pn.torn.goldeneye.napcat.strategy.faction.crime.benefit.OcBenefitQueryStrategyImpl$DateRange");
        Constructor<?> dateRangeCtor = dateRangeClass.getDeclaredConstructor(LocalDateTime.class, LocalDateTime.class);
        dateRangeCtor.setAccessible(true);
        Object dateRange = dateRangeCtor.newInstance(from, to);
        Method method = strategyClass.getDeclaredMethod("queryBenefitList", TornUserDO.class, dateRangeClass);
        method.setAccessible(true);
        method.invoke(strategy, user, dateRange);
    }

    /**
     * 通过反射调用私有方法{@code queryIncomeSummary(Long, LocalDateTime)}。
     *
     * @param userId 用户ID
     * @param toDate 截止时间
     * @return 聚合后的个人summary
     * @throws Exception 反射异常
     */
    private TornFactionOcIncomeSummaryDO invokeQueryIncomeSummary(Long userId, LocalDateTime toDate) throws Exception {
        Method method = strategy.getClass().getDeclaredMethod("queryIncomeSummary", Long.class, LocalDateTime.class);
        method.setAccessible(true);
        return (TornFactionOcIncomeSummaryDO) method.invoke(strategy, userId, toDate);
    }

    /**
     * 通过反射调用私有方法{@code queryOcData(TornUserDO, DateRange)}。
     *
     * @param user 用户
     * @param from 查询开始时间
     * @param to   查询结束时间
     * @return 策略入口查询结果（私有{@code OcDataResult}）
     * @throws Exception 反射异常
     */
    private Object invokeQueryOcData(TornUserDO user, LocalDateTime from, LocalDateTime to) throws Exception {
        Class<?> strategyClass = strategy.getClass();
        Class<?> dateRangeClass = Class.forName(
                "pn.torn.goldeneye.napcat.strategy.faction.crime.benefit.OcBenefitQueryStrategyImpl$DateRange");
        Constructor<?> dateRangeCtor = dateRangeClass.getDeclaredConstructor(LocalDateTime.class, LocalDateTime.class);
        dateRangeCtor.setAccessible(true);
        Object dateRange = dateRangeCtor.newInstance(from, to);
        Method method = strategyClass.getDeclaredMethod("queryOcData", TornUserDO.class, dateRangeClass);
        method.setAccessible(true);
        return method.invoke(strategy, user, dateRange);
    }

    /**
     * 构造一条测试用大锅饭income明细，供收入服务桩返回。
     *
     * @param userId 用户ID
     * @return 测试income记录
     */
    private TornFactionOcIncomeDO buildIncome(Long userId) {
        TornFactionOcIncomeDO income = new TornFactionOcIncomeDO();
        income.setFactionId(TornConstants.FACTION_PN_ID);
        income.setOcId(9900001L);
        income.setUserId(userId);
        return income;
    }

    /**
     * 插入一条测试summary记录，供同月换帮聚合回归用例使用。
     *
     * @param userId      用户ID
     * @param factionId   帮派ID
     * @param yearMonth   年月
     * @param totalReward 总奖励
     */
    private void insertSummary(Long userId, Long factionId, String yearMonth, Long totalReward) {
        TornFactionOcIncomeSummaryDO summary = new TornFactionOcIncomeSummaryDO();
        summary.setUserId(userId);
        summary.setFactionId(factionId);
        summary.setYearMonth(yearMonth);
        summary.setIsSettled(false);
        summary.setTotalEffectiveHours(BigDecimal.valueOf(10));
        summary.setTotalItemCost(0L);
        summary.setTotalReward(totalReward);
        summary.setNetReward(totalReward);
        summary.setFinalIncome(totalReward);
        summary.setOcCount(1);
        summary.setSuccessOcCount(1);
        incomeSummaryDao.save(summary);
    }
}
