package pn.torn.goldeneye.torn.service.faction.oc.planning;

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
 * 帮派OC规划策略解析器。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@Service
@RequiredArgsConstructor
public class OcFactionPlanningPolicyResolver {
    private static final int DEFAULT_RESERVE_PERCENT = 20;
    private static final int DEFAULT_CONSERVATIVE_CAPACITY_PERCENT = 25;
    private static final int DEFAULT_BALANCED_CAPACITY_PERCENT = 50;
    private static final int DEFAULT_PROFIT_CAPACITY_PERCENT = 100;

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
        int reservePercent = policy == null || policy.getNormalPoolReservePercent() == null
                ? DEFAULT_RESERVE_PERCENT : policy.getNormalPoolReservePercent();
        List<String> warnings = new ArrayList<>();
        int conservativeCapacityPercent = resolvePercent(policy == null ? null
                        : policy.getConservativeCapacityPercent(),
                DEFAULT_CONSERVATIVE_CAPACITY_PERCENT, "保守模式安全刷新容量比例", warnings);
        int balancedCapacityPercent = resolvePercent(policy == null ? null
                        : policy.getBalancedCapacityPercent(),
                DEFAULT_BALANCED_CAPACITY_PERCENT, "均衡模式安全刷新容量比例", warnings);
        int profitCapacityPercent = resolvePercent(policy == null ? null
                        : policy.getProfitCapacityPercent(),
                DEFAULT_PROFIT_CAPACITY_PERCENT, "收益模式安全刷新容量比例", warnings);

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
        return new OcFactionPlanningPolicy(factionId, evaluationMode, reservePercent,
                conservativeCapacityPercent, balancedCapacityPercent, profitCapacityPercent,
                enabledKeys, warnings);
    }

    /**
     * 解析容量利用率，未配置时使用默认值，显式非法值记录硬警告。
     *
     * @param configuredValue 配置值
     * @param defaultValue 默认值
     * @param label 配置项名称
     * @param warnings 校验警告集合
     * @return 生效的容量利用率
     */
    private int resolvePercent(Integer configuredValue, int defaultValue, String label,
                               List<String> warnings) {
        if (configuredValue == null) {
            return defaultValue;
        }
        if (configuredValue < 1 || configuredValue > 100) {
            warnings.add(label + "必须在1到100之间，已使用默认值" + defaultValue);
            return defaultValue;
        }
        return configuredValue;
    }
}
