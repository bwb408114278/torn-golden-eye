package pn.torn.goldeneye.torn.service.faction.oc.recommend.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotEligibility;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlannerReplayTestConfiguration;
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlanningReadOnlyGuard;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.OcYieldEstimatorRegistry;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow.OcFlowMarginService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendRanker;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendRanker.OcRankContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kingdee加入前真实阵容的时间排序回放。
 *
 * @author Bai
 * @version 1.9.6
 * @since 2026.10.29
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {OcPlannerReplayTestConfiguration.class, OcRecommendReplayTestConfiguration.class})
@TestPropertySource(properties = {
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=1",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=0",
        "mybatis-plus.type-aliases-package=pn.torn.goldeneye.repository.model"})
@EnabledIfSystemProperty(named = "oc.flow.backtest.enabled", matches = "true")
class OcKingdeeReplayTest {
    private static final long USER_ID = 3133172L;
    private static final long FACTION_ID = 20465L;
    private static final long SHIP_ID = 2375571L;

    @Autowired
    private OcPlanningReadOnlyGuard readOnlyGuard;
    @Autowired
    private TornFactionOcDAO ocDao;
    @Autowired
    private TornFactionOcSlotDAO slotDao;
    @Autowired
    private TornFactionOcUserDAO userDao;
    @Autowired
    private TornOcRecommendManager recommendManager;
    @Autowired
    private OcYieldEstimatorRegistry estimatorRegistry;
    @Autowired
    private OcFlowMarginService marginService;
    @Autowired
    private OcRecommendRanker ranker;

    @Test
    void shipShouldOutrankBtbWhenItStopsEarlier() {
        readOnlyGuard.verifyReadOnlySession();
        readOnlyGuard.inReadOnlyTransaction(status -> {
            List<OcRecommendCandidate> ranked = replayBeforeJoin();
            assertThat(ranked.getFirst().oc().getId()).isEqualTo(SHIP_ID);
            assertThat(ranked.getFirst().slot().getPosition()).isEqualTo("Hustler#1");
            assertThat(ranked.getFirst().marginalValue().signum()).isPositive();
            return null;
        });
    }

    /**
     * 按2026-10-28 22:30（UTC+8）的加入前状态构造候选并执行真实排序。
     *
     * @return 新规则排序结果
     */
    private List<OcRecommendCandidate> replayBeforeJoin() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 28, 14, 30);
        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getId, SHIP_ID, 2384320L, 2375573L).list();
        Map<Long, LocalDateTime> readyTimes = Map.of(
                SHIP_ID, LocalDateTime.of(2026, 10, 28, 15, 40, 58),
                2384320L, LocalDateTime.of(2026, 10, 29, 14, 26, 16),
                2375573L, LocalDateTime.of(2026, 10, 29, 15, 39, 7));
        ocList.forEach(oc -> oc.setReadyTime(readyTimes.get(oc.getId())));
        List<TornFactionOcSlotDO> allSlots = new ArrayList<>(slotDao.queryListByOc(ocList));
        for (TornFactionOcSlotDO slot : allSlots) {
            if ((slot.getOcId().equals(SHIP_ID) && slot.getPosition().equals("Hustler#1"))
                    || (slot.getOcId().equals(2375573L) && slot.getPosition().equals("Muscle#1"))
                    || (slot.getOcId().equals(2384320L) && slot.getPosition().equals("Thief#1"))) {
                slot.setUserId(null);
                slot.setPassRate(null);
            }
        }
        List<TornFactionOcSlotDO> emptySlots = allSlots.stream()
                .filter(slot -> slot.getUserId() == null).toList();
        List<TornFactionOcUserDO> userData = userDao.queryByUserId(USER_ID);
        Map<Long, OcYieldEstimate> estimates = estimatorRegistry.estimateAll(ocList, now);
        List<OcRecommendCandidate> candidates = new ArrayList<>();
        Map<Long, List<TornFactionOcSlotDO>> emptyByOc = emptySlots.stream()
                .collect(Collectors.groupingBy(TornFactionOcSlotDO::getOcId));
        for (TornFactionOcDO oc : ocList) {
            for (TornFactionOcSlotDO slot : emptyByOc.getOrDefault(oc.getId(), List.of())) {
                OcSlotEligibility eligibility = recommendManager.resolveEligibility(FACTION_ID, oc, slot, userData);
                if (eligibility != null && eligibility.eligible()) {
                    candidates.add(new OcRecommendCandidate(oc, slot, eligibility.requirement(),
                            eligibility.passRate(), estimates.get(oc.getId()), null));
                }
            }
        }
        Map<Long, Integer> emptyCounts = new HashMap<>();
        emptySlots.forEach(slot -> emptyCounts.merge(slot.getOcId(), 1, Integer::sum));
        OcRankContext context = new OcRankContext(userDao.queryFactionMatrix(FACTION_ID), emptyCounts, USER_ID, now);
        List<OcRecommendCandidate> withMargins = marginService.apply(candidates, FACTION_ID, ocList, allSlots, Map.of());
        return ranker.rank(withMargins, null, context);
    }
}
