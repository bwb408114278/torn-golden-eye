package pn.torn.goldeneye.torn.service.faction.oc.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanningPolicyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析帮派的新队规划范围与岗位评价模式。
 */
@Component
@RequiredArgsConstructor
public class OcFactionPlanningPolicyResolver {
    private static final String DEFAULT_POOL = "NORMAL_7_8";
    private static final int DEFAULT_RESERVE_PERCENT = 20;

    private final TornSettingOcPlanningManager planningManager;

    /**
     * 解析指定帮派的OC规划策略和显式规划范围。
     *
     * @param factionId 帮派ID
     * @return 应用于该帮派的规划策略
     */
    public OcFactionPlanningPolicy resolve(long factionId) {
        TornSettingFactionOcPlanningPolicyDO policy = planningManager.getPolicies().stream()
                .filter(item -> item.getFactionId().equals(factionId))
                .findFirst().orElse(null);
        OcEvaluationMode evaluationMode = policy == null
                ? OcEvaluationMode.POSITION_WEIGHT
                : OcEvaluationMode.of(policy.getEvaluationMode());
        int reservePercent = policy == null || policy.getNormalPoolReservePercent() == null
                ? DEFAULT_RESERVE_PERCENT : policy.getNormalPoolReservePercent();

        List<TornSettingFactionOcPlanDO> explicit = planningManager.getFactionPlans().stream()
                .filter(item -> item.getFactionId().equals(factionId))
                .toList();
        Set<String> enabled = new HashSet<>();
        if (!explicit.isEmpty()) {
            explicit.forEach(item -> enabled.add(OcPlanningSnapshot.ocKey(item.getRank(), item.getOcName())));
        } else {
            planningManager.getProfiles().stream()
                    .filter(item -> DEFAULT_POOL.equals(item.getSpawnPool()))
                    .filter(item -> "READY".equals(item.getPlanStatus()))
                    .map(this::keyOf)
                    .forEach(enabled::add);
        }
        return new OcFactionPlanningPolicy(factionId, evaluationMode, reservePercent, enabled, List.of());
    }

    private String keyOf(TornSettingOcPlanProfileDO profile) {
        return OcPlanningSnapshot.ocKey(profile.getRank(), profile.getOcName());
    }
}
