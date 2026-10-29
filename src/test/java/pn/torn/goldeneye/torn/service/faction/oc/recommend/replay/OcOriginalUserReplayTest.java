package pn.torn.goldeneye.torn.service.faction.oc.recommend.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原三名真实用户在全类型OC、全部空位场景下的排序回放。
 *
 * @author Bai
 * @version 1.9.6
 * @since 2026.10.29
 */
@Slf4j
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {OcPlannerReplayTestConfiguration.class, OcRecommendReplayTestConfiguration.class})
@TestPropertySource(properties = {
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=1",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=0",
        "mybatis-plus.type-aliases-package=pn.torn.goldeneye.repository.model"})
@EnabledIfSystemProperty(named = "oc.flow.backtest.enabled", matches = "true")
class OcOriginalUserReplayTest {
    private static final long FACTION_ID = 20465L;
    private static final List<Long> USER_IDS = List.of(3359579L, 2554043L, 3973672L);
    private static final List<String> OC_NAMES = List.of(
            "Blast from the Past", "Window of Opportunity", "Cleared for Takeoff",
            "Break the Bank", "Clinical Precision", "Stacking the Deck",
            "Ace in the Hole", "Hostile Takeover");

    @Autowired
    private OcPlanningReadOnlyGuard readOnlyGuard;
    @Autowired
    private TornFactionOcUserDAO ocUserDao;
    @Autowired
    private TornSettingOcSlotManager slotManager;
    @Autowired
    private TornOcRecommendManager recommendManager;
    @Autowired
    private OcYieldEstimatorRegistry estimatorRegistry;
    @Autowired
    private OcFlowMarginService marginService;
    @Autowired
    private OcRecommendRanker ranker;

    @Test
    void replayOriginalUsers() {
        readOnlyGuard.verifyReadOnlySession();
        readOnlyGuard.inReadOnlyTransaction(status -> {
            USER_IDS.forEach(this::replayUser);
            return null;
        });
    }

    /**
     * 使用通用岗位表与全部空位回放单个真实用户。
     *
     * @param userId 真实用户ID
     */
    private void replayUser(long userId) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 12, 0);
        List<TornFactionOcUserDO> userData = ocUserDao.queryByUserId(userId);
        List<TornSettingOcSlotDO> settings = slotManager.getList().stream()
                .filter(slot -> OC_NAMES.contains(slot.getOcName())).toList();
        List<TornFactionOcDO> ocList = new ArrayList<>();
        List<TornFactionOcSlotDO> allSlots = new ArrayList<>();
        List<OcRecommendCandidate> candidates = new ArrayList<>();
        Map<Long, Integer> emptySlots = new HashMap<>();
        long ocId = 10_000L;
        long slotId = 100_000L;
        for (String ocName : OC_NAMES) {
            List<TornSettingOcSlotDO> ocSettings = settings.stream()
                    .filter(slot -> slot.getOcName().equals(ocName)).toList();
            TornFactionOcDO oc = new TornFactionOcDO();
            oc.setId(ocId++);
            oc.setFactionId(FACTION_ID);
            oc.setName(ocName);
            oc.setRank(ocSettings.getFirst().getRank());
            ocList.add(oc);
            for (TornSettingOcSlotDO setting : ocSettings) {
                TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
                slot.setId(slotId++);
                slot.setOcId(oc.getId());
                slot.setPosition(setting.getSlotCode());
                allSlots.add(slot);
                emptySlots.merge(oc.getId(), 1, Integer::sum);
                TornFactionOcUserDO passRate = recommendManager.findUserPassRate(userData, oc, setting);
                if (passRate != null && passRate.getPassRate() >= setting.getPassRate()) {
                    candidates.add(new OcRecommendCandidate(oc, slot, setting,
                            passRate.getPassRate(), null, null));
                }
            }
        }
        Map<Long, OcYieldEstimate> estimates = estimatorRegistry.estimateAll(ocList, now);
        candidates = candidates.stream()
                .map(candidate -> new OcRecommendCandidate(candidate.oc(), candidate.slot(), candidate.requirement(),
                        candidate.passRate(), estimates.get(candidate.oc().getId()), null))
                .toList();
        candidates = marginService.apply(candidates, 0L, ocList, allSlots, Map.of());
        OcRankContext context = new OcRankContext(ocUserDao.queryFactionMatrix(FACTION_ID), emptySlots, userId, now);
        List<OcRecommendCandidate> ranked = ranker.rank(candidates, null, context);
        assertThat(ranked).isNotEmpty();
        ranked.stream().limit(6).forEach(candidate -> log.info(
                "ORIGINAL user={} oc={} rank={} slot={} rating={} margin={} ready={}",
                userId, candidate.oc().getName(), candidate.oc().getRank(), candidate.slot().getPosition(),
                candidate.score().rating(), candidate.marginalValue(), candidate.oc().getReadyTime()));
    }
}
