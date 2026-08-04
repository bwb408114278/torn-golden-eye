package pn.torn.goldeneye.repository.mapper.faction.oc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitUserRankDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 普通收益排除规则数据库边界测试。
 *
 * <p>验证排行榜（指定帮派榜、SMTH总榜、用户个人排名）与个人普通收益明细共用同一套
 * 按帮派+OC名称+生效时间的大锅饭排除规则，日期边界结论一致。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("OC收益排除规则数据库边界测试")
class TornFactionOcBenefitMapperTest {
    @Autowired
    private TornFactionOcBenefitDAO benefitDao;

    private static final Long PN_USER = 8803001L;
    private static final Long NOV_USER = 8803002L;
    private static final Long OTHER_USER = 8803003L;
    private static final Long OTHER_FACTION = 9999L;

    @BeforeEach
    void setUp() {
        // PN：生效前普通收益保留、原有名单始终排除
        insertBenefit(PN_USER, TornConstants.FACTION_PN_ID, TornConstants.OC_NAME_LOCK_STOCK,
                LocalDateTime.of(2026, 7, 31, 10, 0), 100L);
        insertBenefit(PN_USER, TornConstants.FACTION_PN_ID, TornConstants.OC_NAME_ACE_IN_THE_HOLE,
                LocalDateTime.of(2026, 7, 15, 10, 0), 200L);
        insertBenefit(PN_USER, TornConstants.FACTION_PN_ID, TornConstants.OC_NAME_HOSTILE_TAKEOVER,
                LocalDateTime.of(2026, 7, 30, 10, 0), 400L);
        insertBenefit(PN_USER, TornConstants.FACTION_PN_ID, TornConstants.OC_NAME_LOCK_STOCK,
                LocalDateTime.of(2026, 8, 1, 10, 0), 300L);

        // NOV：生效前普通收益保留、生效后排除
        insertBenefit(NOV_USER, TornConstants.FACTION_NOV_ID, TornConstants.OC_NAME_STACKING_THE_DECK,
                LocalDateTime.of(2026, 6, 30, 10, 0), 500L);
        insertBenefit(NOV_USER, TornConstants.FACTION_NOV_ID, TornConstants.OC_NAME_STACKING_THE_DECK,
                LocalDateTime.of(2026, 7, 1, 10, 0), 600L);

        // 普通帮派收益，任何月份都保留
        insertBenefit(OTHER_USER, OTHER_FACTION, "Any OC",
                LocalDateTime.of(2026, 8, 5, 10, 0), 800L);
    }

    @Test
    @DisplayName("PN个人普通收益明细：生效前保留、生效后与原有名单排除")
    void personalBenefit_pnDateBoundary() {
        List<TornFactionOcBenefitDO> july = benefitDao.queryPersonalBenefitList(
                personalQuery(TornConstants.FACTION_PN_ID, PN_USER,
                        LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 31, 23, 59, 59)));

        assertEquals(2, july.size());
        assertTrue(july.stream().anyMatch(b -> b.getOcName().equals(TornConstants.OC_NAME_LOCK_STOCK)));
        assertTrue(july.stream().anyMatch(b -> b.getOcName().equals(TornConstants.OC_NAME_HOSTILE_TAKEOVER)));
        assertFalse(july.stream().anyMatch(b -> b.getOcName().equals(TornConstants.OC_NAME_ACE_IN_THE_HOLE)));

        List<TornFactionOcBenefitDO> august = benefitDao.queryPersonalBenefitList(
                personalQuery(TornConstants.FACTION_PN_ID, PN_USER,
                        LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 31, 23, 59, 59)));
        assertTrue(august.isEmpty());
    }

    @Test
    @DisplayName("NOV个人普通收益明细：生效前保留、生效后排除")
    void personalBenefit_novDateBoundary() {
        List<TornFactionOcBenefitDO> june = benefitDao.queryPersonalBenefitList(
                personalQuery(TornConstants.FACTION_NOV_ID, NOV_USER,
                        LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 23, 59, 59)));
        assertEquals(1, june.size());
        assertEquals(TornConstants.OC_NAME_STACKING_THE_DECK, june.getFirst().getOcName());

        List<TornFactionOcBenefitDO> july = benefitDao.queryPersonalBenefitList(
                personalQuery(TornConstants.FACTION_NOV_ID, NOV_USER,
                        LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 31, 23, 59, 59)));
        assertTrue(july.isEmpty());
    }

    @Test
    @DisplayName("PN帮派收益榜：生效前普通收益计入、生效后排除")
    void factionRanking_pnDateBoundary() {
        OcBenefitRankingQuery julyQuery =
                new OcBenefitRankingQuery(TornConstants.FACTION_PN_ID, 0L, LocalDate.of(2026, 7, 1));
        julyQuery.setLimit(10000);
        List<TornFactionOcBenefitRankDO> july = benefitDao.queryBenefitRanking(julyQuery);
        TornFactionOcBenefitRankDO pnUserJuly = findRank(july, PN_USER);
        assertNotNull(pnUserJuly);
        // 生效前：Lock Stock(100) + Hostile Takeover(400) 计入，Ace in the Hole 始终排除
        assertEquals(500L, pnUserJuly.getBenefit());

        OcBenefitRankingQuery augustQuery =
                new OcBenefitRankingQuery(TornConstants.FACTION_PN_ID, 0L, LocalDate.of(2026, 8, 1));
        augustQuery.setLimit(10000);
        List<TornFactionOcBenefitRankDO> august = benefitDao.queryBenefitRanking(augustQuery);
        // 生效后：Lock Stock 被排除，PN榜无该用户普通收益
        assertNull(findRank(august, PN_USER));
    }

    @Test
    @DisplayName("SMTH总榜：普通帮派收益计入、生效后目标OC普通收益排除")
    void smthRanking_keepsOrdinaryAndExcludesScheduled() {
        OcBenefitRankingQuery augustQuery = new OcBenefitRankingQuery(0L, 0L, LocalDate.of(2026, 8, 1));
        augustQuery.setLimit(10000);
        List<TornFactionOcBenefitRankDO> august = benefitDao.queryBenefitRanking(augustQuery);
        // 普通帮派收益进入总榜
        TornFactionOcBenefitRankDO otherUser = findRank(august, OTHER_USER);
        assertNotNull(otherUser);
        assertEquals(800L, otherUser.getBenefit());
        // PN生效后的Lock Stock普通收益不得进入总榜
        assertNull(findRank(august, PN_USER));
    }

    @Test
    @DisplayName("用户个人排名与帮派榜使用同一排除结论")
    void userRanking_consistentWithFactionRanking() {
        TornFactionOcBenefitUserRankDO ranking = benefitDao.queryBenefitUserRanking(
                new OcBenefitRankingQuery(PN_USER, LocalDate.of(2026, 7, 1)));
        assertNotNull(ranking);
        assertEquals(500L, ranking.getBenefit());
    }

    @Test
    @DisplayName("同期榜：生效前普通收益计入、生效后目标OC普通收益排除")
    void cohortRanking_appliesSameExclusionRule() {
        // PN_USER=8803001、NOV_USER=8803002、OTHER_USER=8803003 同为880同期组
        OcBenefitRankingQuery julyQuery = new OcBenefitRankingQuery(PN_USER, LocalDate.of(2026, 7, 1));
        julyQuery.setLimit(10000);
        List<TornFactionOcBenefitRankDO> july = benefitDao.queryCohortBenefitRanking(julyQuery);
        TornFactionOcBenefitRankDO pnUserJuly = findRank(july, PN_USER);
        assertNotNull(pnUserJuly);
        // 生效前：Lock Stock(100) + Hostile Takeover(400) 计入，Ace in the Hole 始终排除
        assertEquals(500L, pnUserJuly.getBenefit());

        OcBenefitRankingQuery augustQuery = new OcBenefitRankingQuery(PN_USER, LocalDate.of(2026, 8, 1));
        augustQuery.setLimit(10000);
        List<TornFactionOcBenefitRankDO> august = benefitDao.queryCohortBenefitRanking(augustQuery);
        // 生效后：PN Lock Stock 普通收益被排除，同期榜不再出现该用户
        assertNull(findRank(august, PN_USER));
    }

    private OcBenefitRankingQuery personalQuery(long factionId, long userId,
                                                LocalDateTime from, LocalDateTime to) {
        return new OcBenefitRankingQuery(factionId, userId, from, to);
    }

    private TornFactionOcBenefitRankDO findRank(List<TornFactionOcBenefitRankDO> rankList, Long userId) {
        return rankList.stream()
                .filter(r -> userId.equals(r.getUserId()))
                .findFirst()
                .orElse(null);
    }

    private void insertBenefit(Long userId, Long factionId, String ocName,
                               LocalDateTime finishTime, Long netReward) {
        TornFactionOcBenefitDO benefit = new TornFactionOcBenefitDO();
        benefit.setUserId(userId);
        benefit.setFactionId(factionId);
        benefit.setOcId(userId * 100000L + (finishTime.getMonthValue() * 100L + finishTime.getDayOfMonth()));
        benefit.setOcName(ocName);
        benefit.setOcRank(8);
        benefit.setOcStatus(TornOcStatusEnum.SUCCESSFUL.getCode());
        benefit.setOcFinishTime(finishTime);
        benefit.setUserPosition("Hacker#1");
        benefit.setUserPassRate(65);
        benefit.setBenefitMoney(netReward);
        benefit.setItemCost(0L);
        benefit.setNetReward(netReward);
        benefitDao.save(benefit);
    }
}
