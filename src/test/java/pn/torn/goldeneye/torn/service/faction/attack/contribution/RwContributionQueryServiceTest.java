package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwRankDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionReportBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionRowBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionWarBO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RW真赛贡献榜查询编排测试。
 *
 * <p>只验证入选口径、未结算标记、聚合与并列排序，得分边界已由计算器测试完整覆盖。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW真赛贡献榜查询编排测试")
class RwContributionQueryServiceTest {
    private static final long FACTION_ID = 20465L;

    @Mock
    private TornFactionRwDAO rwDao;
    @Mock
    private TornFactionRwRankDAO rankDao;

    private RwContributionQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new RwContributionQueryService(rwDao, rankDao, new RwContributionCalculator());
    }

    @Test
    @DisplayName("3场合格真赛按系数聚合总分并只取前3场")
    void buildReport_threeQualifiedWars_aggregatesScore() {
        LambdaQueryChainWrapper<TornFactionRwDO> rwQuery = stubWarQuery(List.of(
                war(48522L, 22625, "DA", "Destructive Anomaly", LocalDateTime.of(2026, 9, 6, 14, 40)),
                war(46672L, 3752, "TNL-Forge", "The Next Level - Forge", LocalDateTime.of(2026, 8, 1, 5, 8)),
                war(44155L, 16126, "Arcadia", "Arcadia", LocalDateTime.of(2026, 6, 20, 5, 0))));
        stubRankQuery(List.of(
                rank(48522L, 1001L, "Cinderine", 1),
                rank(46672L, 1001L, "Cinderine", 1),
                rank(44155L, 1001L, "Cinderine", 1),
                rank(48522L, 1002L, "NoZuoNoDie", 9)));

        RwContributionReportBO report = queryService.buildReport(FACTION_ID);

        verify(rwQuery).last("LIMIT 3");
        assertEquals(3, report.wars().size());
        assertWar(report.wars().get(0), 48522L, "DA", 22625, "1.2", true);
        assertWar(report.wars().get(1), 46672L, "TNL-Forge", 3752, "0.5", true);
        assertWar(report.wars().get(2), 44155L, "Arcadia", 16126, "1.1", true);
        assertEquals(2, report.rows().size());
        RwContributionRowBO first = report.rows().getFirst();
        assertEquals(1001L, first.userId());
        assertEquals(0, new BigDecimal("280.0").compareTo(first.totalScore()));
        assertEquals(3, first.warCount());
        assertEquals(1, first.rankByRwId().get(48522L));
        RwContributionRowBO second = report.rows().get(1);
        assertEquals(1002L, second.userId());
        assertEquals(0, new BigDecimal("110.4").compareTo(second.totalScore()));
        assertEquals(1, second.warCount());
    }

    @Test
    @DisplayName("合格场次不足3场时按实际场次出榜且总分只累加现有场次")
    void buildReport_lessThanThreeWars_sumsAvailableWars() {
        stubWarQuery(List.of(war(48522L, 22625, "DA", "Destructive Anomaly", LocalDateTime.of(2026, 9, 6, 14, 40))));
        stubRankQuery(List.of(rank(48522L, 1001L, "Cinderine", 1)));

        RwContributionReportBO report = queryService.buildReport(FACTION_ID);

        assertEquals(1, report.wars().size());
        assertEquals(1, report.rows().size());
        assertEquals(0, new BigDecimal("120.0").compareTo(report.rows().getFirst().totalScore()));
    }

    @Test
    @DisplayName("无合格场次时直接返回空报告且不查询结算表")
    void buildReport_noQualifiedWar_returnsEmptyReport() {
        stubWarQuery(List.of());

        RwContributionReportBO report = queryService.buildReport(FACTION_ID);

        assertTrue(report.wars().isEmpty());
        assertTrue(report.rows().isEmpty());
        verify(rankDao, never()).lambdaQuery();
    }

    @Test
    @DisplayName("某场缺少结算行时标记未结算且不阻塞其余场次")
    void buildReport_missingSettlement_marksWarUnsettled() {
        stubWarQuery(List.of(
                war(48522L, 22625, "DA", "Destructive Anomaly", LocalDateTime.of(2026, 9, 6, 14, 40)),
                war(46672L, 3752, "TNL-Forge", "The Next Level - Forge", LocalDateTime.of(2026, 8, 1, 5, 8))));
        stubRankQuery(List.of(rank(48522L, 1001L, "Cinderine", 1)));

        RwContributionReportBO report = queryService.buildReport(FACTION_ID);

        assertTrue(report.wars().get(0).settled());
        assertFalse(report.wars().get(1).settled());
        assertEquals(1, report.rows().size());
        assertFalse(report.rows().getFirst().rankByRwId().containsKey(46672L));
    }

    @Test
    @DisplayName("总分并列时先按参加场次数多者优先再按用户ID升序")
    void buildReport_tieBreakByWarCountThenUserId() {
        stubWarQuery(List.of(
                war(48522L, 6000, "DA", "Destructive Anomaly", LocalDateTime.of(2026, 9, 6, 14, 40)),
                war(46672L, 6000, "TNL-Forge", "The Next Level - Forge", LocalDateTime.of(2026, 8, 1, 5, 8))));
        stubRankQuery(List.of(
                rank(48522L, 30L, "Thirty", 1),
                rank(48522L, 20L, "Twenty", 1),
                rank(48522L, 40L, "Forty", 51),
                rank(46672L, 40L, "Forty", 51)));

        RwContributionReportBO report = queryService.buildReport(FACTION_ID);

        assertEquals(3, report.rows().size());
        assertEquals(40L, report.rows().get(0).userId(), "场次多的成员应排在并列总分之前");
        assertEquals(2, report.rows().get(0).warCount());
        assertEquals(20L, report.rows().get(1).userId(), "同分同场次时按用户ID升序");
        assertEquals(30L, report.rows().get(2).userId());
    }

    private LambdaQueryChainWrapper<TornFactionRwDO> stubWarQuery(List<TornFactionRwDO> wars) {
        LambdaQueryChainWrapper<TornFactionRwDO> query = mock(LambdaQueryChainWrapper.class);
        when(rwDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.isNotNull(any(SFunction.class))).thenReturn(query);
        when(query.gt(any(SFunction.class), any())).thenReturn(query);
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        when(query.list()).thenReturn(wars);
        return query;
    }

    private void stubRankQuery(List<TornFactionRwRankDO> ranks) {
        LambdaQueryChainWrapper<TornFactionRwRankDO> query = mock(LambdaQueryChainWrapper.class);
        when(rankDao.lambdaQuery()).thenReturn(query);
        when(query.in(any(SFunction.class), anyCollection())).thenReturn(query);
        when(query.list()).thenReturn(ranks);
    }

    private void assertWar(RwContributionWarBO war, long rwId, String shortName, int opponentScore,
                           String coefficient, boolean settled) {
        assertEquals(rwId, war.rwId());
        assertEquals(shortName, war.opponentShortName());
        assertEquals(opponentScore, war.opponentScore());
        assertEquals(0, new BigDecimal(coefficient).compareTo(war.coefficient()));
        assertEquals(settled, war.settled());
    }

    private TornFactionRwDO war(long rwId, int opponentScore, String opponentShortName, String opponentName,
                                LocalDateTime endTime) {
        TornFactionRwDO war = new TornFactionRwDO();
        war.setId(rwId);
        war.setFactionId(FACTION_ID);
        war.setOpponentScore(opponentScore);
        war.setOpponentShortName(opponentShortName);
        war.setOpponentFactionName(opponentName);
        war.setEndTime(endTime);
        return war;
    }

    private TornFactionRwRankDO rank(long rwId, long userId, String nickname, int rankNum) {
        TornFactionRwRankDO rank = new TornFactionRwRankDO();
        rank.setRwId(rwId);
        rank.setUserId(userId);
        rank.setNickname(nickname);
        rank.setRankNum(rankNum);
        return rank;
    }
}
