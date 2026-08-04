package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.FactionOcExclusion;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * OC收益查询策略编排测试。
 *
 * <p>验证策略入口确实构造带所属帮派大锅饭排除规则的{@link OcBenefitRankingQuery}，
 * 并调用生产DAO的{@code queryPersonalBenefitList}。本测试是入口接线证据，不能替代真实Mapper数据库测试，
 * 日期边界与排除结论以{@code TornFactionOcBenefitMapperTest}为准。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.04
 */
@SpringBootTest
@DisplayName("OC收益查询策略编排测试")
class OcBenefitQueryStrategyImplTest {
    @Autowired
    private OcBenefitQueryStrategyImpl strategy;
    @MockitoSpyBean
    private TornFactionOcBenefitDAO benefitDao;

    @Test
    @DisplayName("NOV用户构造带帮派排除规则的查询并调用生产DAO")
    void queryBenefitList_buildsFactionExclusionQuery() throws Exception {
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
        assertEquals(TornConstants.FACTION_NOV_ID, query.getFactionId());
        assertEquals(8809001L, query.getUserId());
        assertEquals(from, query.getFromDate());
        assertEquals(to, query.getToDate());
        List<FactionOcExclusion> rules = query.getFactionOcExclusions();
        assertFalse(rules.isEmpty());
        // 规则只属于NOV，且包含带生效时间的NOV新增名单
        assertTrue(rules.stream().allMatch(r -> TornConstants.FACTION_NOV_ID == r.getFactionId()));
        assertTrue(rules.stream().anyMatch(r -> r.getEffectiveFrom() != null
                && r.getOcList().contains(TornConstants.OC_NAME_LOCK_STOCK)));
    }

    @Test
    @DisplayName("非大锅饭帮派用户构造无排除规则查询")
    void queryBenefitList_normalFaction_noExclusionRules() throws Exception {
        TornUserDO user = new TornUserDO();
        user.setId(8809002L);
        user.setFactionId(9999L);
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 31, 23, 59, 59);

        invokeQueryBenefitList(user, from, to);

        ArgumentCaptor<OcBenefitRankingQuery> queryCaptor = ArgumentCaptor.forClass(OcBenefitRankingQuery.class);
        verify(benefitDao).queryPersonalBenefitList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getFactionOcExclusions().isEmpty());
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
}
