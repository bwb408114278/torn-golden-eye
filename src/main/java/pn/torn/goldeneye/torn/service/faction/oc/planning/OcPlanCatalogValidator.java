package pn.torn.goldeneye.torn.service.faction.oc.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcCoefficientDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcEvaluationMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanStatus;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OC规划目录完整性校验器。不完整的READY配置会被降级并输出明确警告。
 */
@Component
@RequiredArgsConstructor
public class OcPlanCatalogValidator {
    private final TornSettingOcManager ocManager;
    private final TornSettingOcSlotManager slotManager;
    private final TornSettingOcCoefficientManager coefficientManager;
    private final TornSettingOcPlanningManager planningManager;

    public OcCatalogValidationResult validate(OcFactionPlanningPolicy policy) {
        Map<String, TornSettingOcDO> templates = new HashMap<>();
        ocManager.getList().forEach(item -> templates.put(key(item.getRank(), item.getOcName()), item));
        Map<String, List<TornSettingOcSlotDO>> slots = new HashMap<>();
        slotManager.getList().forEach(item -> slots.computeIfAbsent(key(item.getRank(), item.getOcName()),
                ignored -> new ArrayList<>()).add(item));
        List<TornSettingOcCoefficientDO> coefficients = coefficientManager.getList();
        List<String> warnings = new ArrayList<>();
        Set<String> invalidOcKeys = new HashSet<>();
        for (TornSettingOcPlanProfileDO profile : planningManager.getProfiles()) {
            String ocKey = key(profile.getRank(), profile.getOcName());
            if (!policy.enabledOcKeys().contains(ocKey)
                    || !OcPlanStatus.READY.equals(OcPlanStatus.of(profile.getPlanStatus()))) {
                continue;
            }
            TornSettingOcDO template = templates.get(ocKey);
            List<TornSettingOcSlotDO> ocSlots = slots.getOrDefault(ocKey, List.of());
            if (template == null || template.getRequiredMembers() == null
                    || template.getRequiredMembers() <= 0
                    || template.getPrepareDays() == null || template.getPrepareDays() <= 0) {
                warnings.add(profile.getOcName() + " 缺少有效人数或准备天数，已禁止自动规划");
                invalidOcKeys.add(ocKey);
                continue;
            }
            if (ocSlots.size() != template.getRequiredMembers()) {
                warnings.add(profile.getOcName() + " 岗位数与人数不一致，已禁止自动规划");
                invalidOcKeys.add(ocKey);
            }
            if (OcEvaluationMode.DIFFERENTIAL_WORK_HOUR.equals(policy.evaluationMode())) {
                boolean complete = ocSlots.stream().allMatch(slot -> coefficients.stream()
                        .anyMatch(coefficient -> (coefficient.getFactionId().equals(policy.factionId())
                                || coefficient.getFactionId().equals(0L))
                                && coefficient.getOcName().equals(profile.getOcName())
                                && coefficient.getRank().equals(profile.getRank())
                                && coefficient.getSlotCode().equals(slot.getSlotCode())));
                if (!complete) {
                    warnings.add(profile.getOcName() + " 缺少完整差异化工时系数，已禁止自动规划");
                    invalidOcKeys.add(ocKey);
                }
            }
        }
        warnings.addAll(validateChainGraph());
        return new OcCatalogValidationResult(warnings, invalidOcKeys);
    }

    private List<String> validateChainGraph() {
        List<String> warnings = new ArrayList<>();
        Map<String, Set<String>> graph = new HashMap<>();
        for (TornSettingOcChainDO chain : planningManager.getChains()) {
            String parent = key(chain.getParentRank(), chain.getParentOcName());
            String child = key(chain.getChildRank(), chain.getChildOcName());
            graph.computeIfAbsent(parent, ignored -> new HashSet<>()).add(child);
        }
        for (String node : graph.keySet()) {
            if (hasCycle(node, node, graph, new HashSet<>())) {
                warnings.add("OC链配置存在环: " + node);
            }
        }
        return warnings;
    }

    private boolean hasCycle(String origin, String current, Map<String, Set<String>> graph,
                             Set<String> path) {
        if (!path.add(current)) {
            return origin.equals(current);
        }
        for (String child : graph.getOrDefault(current, Set.of())) {
            if (child.equals(origin) || hasCycle(origin, child, graph, new HashSet<>(path))) {
                return true;
            }
        }
        return false;
    }

    private String key(int rank, String name) {
        return OcPlanningSnapshot.ocKey(rank, name);
    }
}
