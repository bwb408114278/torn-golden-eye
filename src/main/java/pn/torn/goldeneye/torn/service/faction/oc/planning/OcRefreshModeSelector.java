package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcFactionPlanningPolicy;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 根据模式容量利用率从安全前沿中选择刷新指令。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@Component
public class OcRefreshModeSelector {

    /**
     * 选择指定模式的安全刷新向量。
     *
     * @param safety 安全前沿求解结果
     * @param policy 帮派规划策略
     * @param mode 刷新策略模式
     * @return 不超过安全前沿和模式容量利用率的刷新向量
     */
    public OcRefreshVector select(OcRefreshSafetyResult safety,
                                  OcFactionPlanningPolicy policy,
                                  OcPlanMode mode) {
        List<OcRefreshVector> safeVectors = expandSafeVectors(safety.frontier());
        int maxTotal = safeVectors.stream().mapToInt(OcRefreshVector::totalCount).max().orElse(0);
        if (maxTotal == 0) {
            return new OcRefreshVector(0, 0);
        }
        int target = maxTotal * capacityPercent(policy, mode) / 100;
        Comparator<OcRefreshVector> comparator = comparator(mode);
        return safeVectors.stream()
                .filter(vector -> vector.totalCount() <= target)
                .max(comparator)
                .orElse(new OcRefreshVector(0, 0));
    }

    /**
     * 将安全前沿展开为所有被前沿向量包含的安全子向量。
     *
     * @param frontier 已证明安全的前沿向量
     * @return 去重后的全部安全子向量
     */
    private List<OcRefreshVector> expandSafeVectors(List<OcRefreshVector> frontier) {
        List<OcRefreshVector> result = new ArrayList<>();
        for (OcRefreshVector bound : frontier) {
            for (int normal = 0; normal <= bound.normalCount(); normal++) {
                for (int high = 0; high <= bound.highCount(); high++) {
                    OcRefreshVector candidate = new OcRefreshVector(normal, high);
                    if (!result.contains(candidate)) {
                        result.add(candidate);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 获取指定模式配置的安全容量利用率。
     *
     * @param policy 帮派规划策略
     * @param mode 规划模式
     * @return 容量利用率百分比
     */
    private int capacityPercent(OcFactionPlanningPolicy policy, OcPlanMode mode) {
        return switch (mode) {
            case CONSERVATIVE -> policy.conservativeCapacityPercent();
            case BALANCED -> policy.balancedCapacityPercent();
            case PROFIT -> policy.profitCapacityPercent();
        };
    }

    /**
     * 构造指定模式的安全向量排序器。
     *
     * @param mode 规划模式
     * @return 模式对应的向量排序器
     */
    private Comparator<OcRefreshVector> comparator(OcPlanMode mode) {
        return switch (mode) {
            case CONSERVATIVE -> Comparator.comparingInt(OcRefreshVector::totalCount)
                    .thenComparingInt(OcRefreshVector::normalCount)
                    .thenComparingInt(vector -> -vector.highCount());
            case BALANCED -> Comparator.comparingInt(OcRefreshVector::totalCount)
                    .thenComparingInt((OcRefreshVector vector) -> -Math.abs(
                            vector.normalCount() - vector.highCount()))
                    .thenComparingInt(OcRefreshVector::normalCount);
            case PROFIT -> Comparator.comparingInt(OcRefreshVector::highCount)
                    .thenComparingInt(OcRefreshVector::totalCount)
                    .thenComparingInt(OcRefreshVector::normalCount);
        };
    }
}
