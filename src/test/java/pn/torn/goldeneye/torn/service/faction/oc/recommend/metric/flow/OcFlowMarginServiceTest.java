package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import org.junit.jupiter.api.DisplayName;
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
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcFlowManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotEligibility;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowKey;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowValuation;
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlannerReplayTestConfiguration;
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlanningReadOnlyGuard;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.replay.OcRecommendReplayTestConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 使用真实配置和3359579能力快照验证DAG边际接线。
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.27
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {OcPlannerReplayTestConfiguration.class, OcRecommendReplayTestConfiguration.class})
@TestPropertySource(properties = {
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=1",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=0",
        "mybatis-plus.type-aliases-package=pn.torn.goldeneye.repository.model"})
@EnabledIfSystemProperty(named = "oc.flow.backtest.enabled", matches = "true")
@DisplayName("DAG边际真实数据接线验证")
class OcFlowMarginServiceTest {
    private static final long FACTION_ID = 20465L;
    private static final long USER_ID = 3359579L;
    private static final List<String> OC_NAMES = List.of(
            "Blast from the Past", "Window of Opportunity", "Cleared for Takeoff",
            "Break the Bank", "Clinical Precision", "Stacking the Deck",
            "Ace in the Hole", "Hostile Takeover");

    @Autowired
    private OcPlanningReadOnlyGuard readOnlyGuard;
    @Autowired
    private OcFlowMarginService marginService;
    @Autowired
    private TornFactionOcUserDAO ocUserDao;
    @Autowired
    private TornSettingOcSlotManager slotManager;
    @Autowired
    private TornOcRecommendManager recommendManager;
    @Autowired
    private TornSettingOcFlowManager flowManager;
    @Autowired
    private OcFlowValuator valuator;

    @Test
    @DisplayName("真实DO与图装配能复算逐坑及链式边际")
    void apply_shouldMatchMeasuredDagMargins() {
        readOnlyGuard.verifyReadOnlySession();
        readOnlyGuard.inReadOnlyTransaction(status -> {
            verifyMargins();
            return null;
        });
    }

    private void verifyMargins() {
        List<TornFactionOcUserDO> userData = ocUserDao.queryByUserId(USER_ID);
        List<TornSettingOcSlotDO> settings = slotManager.getList().stream()
                .filter(slot -> OC_NAMES.contains(slot.getOcName()))
                .toList();
        Map<String, Double> aceRates = settings.stream()
                .filter(slot -> slot.getOcName().equals("Ace in the Hole"))
                .collect(Collectors.toMap(slot -> OcFlowSlotCodes.normalize(slot.getSlotCode()),
                        OcFlowSubstitutePolicy::substituteRate));
        OcFlowValuation aceBaseline = valuator.evaluate(
                flowManager.getGraphs().get(OcFlowKey.of("Ace in the Hole", 9)), aceRates, null).orElseThrow();
        assertThat(aceBaseline.expectedValue())
                .isCloseTo(new BigDecimal("307306000"), within(new BigDecimal("5000")));
        List<TornFactionOcDO> ocList = new ArrayList<>();
        List<TornFactionOcSlotDO> allSlots = new ArrayList<>();
        List<OcRecommendCandidate> candidates = new ArrayList<>();
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
                TornFactionOcUserDO passRate = recommendManager.findUserPassRate(userData, oc, setting);
                OcSlotEligibility eligibility = new OcSlotEligibility(setting,
                        passRate == null ? null : passRate.getPassRate());
                if (eligibility != null && eligibility.eligible()) {
                    candidates.add(new OcRecommendCandidate(oc, slot, eligibility.requirement(),
                            eligibility.passRate(), null, null));
                }
            }
        }

        List<OcRecommendCandidate> result = marginService.apply(candidates, 0L,
                ocList, allSlots, Map.of());
        Map<String, OcRecommendCandidate> bySlot = result.stream().collect(Collectors.toMap(
                candidate -> candidate.oc().getName() + "|" + candidate.slot().getPosition(), Function.identity()));

        assertThat(result).hasSize(33);
        assertMargin(bySlot, "Break the Bank|Thief#2", "1599800");
        assertMargin(bySlot, "Clinical Precision|Imitator#1", "2302000");
        assertMargin(bySlot, "Stacking the Deck|Cat Burglar#1", "2910000");
        assertMargin(bySlot, "Hostile Takeover|Hacker#1", "7881000");
        assertThat(bySlot.get("Stacking the Deck|Imitator#1").marginalValue().signum()).isZero();
    }

    private void assertMargin(Map<String, OcRecommendCandidate> bySlot, String key, String expected) {
        assertThat(bySlot).containsKey(key);
        assertThat(bySlot.get(key).marginalValue())
                .isCloseTo(new BigDecimal(expected), within(new BigDecimal("5000")));
    }
}
