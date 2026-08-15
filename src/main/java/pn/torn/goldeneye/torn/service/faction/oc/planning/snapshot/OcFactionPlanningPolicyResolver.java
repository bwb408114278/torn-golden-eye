package pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanningPolicyDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 帮派OC规划策略解析器。只解析评价模式和显式规划范围；
 * 旧容量比例字段保留在表中但不再参与新模式选择。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Service
@RequiredArgsConstructor
public class OcFactionPlanningPolicyResolver {
    private final TornSettingOcPlanningManager planningManager;

    /**
     * 解析指定帮派的OC规划策略和显式规划范围。
     *
     * @param factionId 帮派ID
     * @return 应用于该帮派的规划策略
     */
    public OcFactionPlanningPolicy resolve(long factionId) {
        List<TornSettingFactionOcPlanningPolicyDO> policies = planningManager.getPolicies();
        TornSettingFactionOcPlanningPolicyDO policy = policies.stream()
                .filter(item -> item.getFactionId().equals(factionId))
                .findFirst()
                .orElseGet(() -> policies.stream()
                        .filter(item -> item.getFactionId().equals(0L))
                        .findFirst().orElse(null));
        OcEvaluationMode evaluationMode = policy == null
                ? OcEvaluationMode.POSITION_WEIGHT : OcEvaluationMode.of(policy.getEvaluationMode());
        List<String> warnings = new ArrayList<>();
        List<TornSettingFactionOcPlanDO> explicit = planningManager.getFactionPlans().stream()
                .filter(item -> item.getFactionId().equals(factionId))
                .toList();
        Set<String> enabledKeys = new HashSet<>();
        if (explicit.isEmpty()) {
            warnings.add("帮派未配置显式OC规划范围，自动规划已禁用");
        } else {
            explicit.stream().filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                    .map(item -> OcPlanningSnapshot.ocKey(item.getRank(), item.getOcName()))
                    .forEach(enabledKeys::add);
        }
        if (enabledKeys.isEmpty()) {
            warnings.add("帮派没有启用的OC规划项，自动规划已禁用");
        }
        return new OcFactionPlanningPolicy(factionId, evaluationMode, enabledKeys, warnings);
    }
}
