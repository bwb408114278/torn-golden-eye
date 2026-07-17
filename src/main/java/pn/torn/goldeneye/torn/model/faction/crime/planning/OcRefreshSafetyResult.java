package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;

/**
 * 联合刷新安全边界求解结果。
 *
 * @param frontier 已证明安全且不能再增加任一池次数的前沿向量
 * @param lowerBound 是否因时间预算或搜索上限仅得到安全下界
 * @param elapsedMillis 求解耗时毫秒数
 * @param warnings 求解警告
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record OcRefreshSafetyResult(List<OcRefreshVector> frontier,
                                    boolean lowerBound,
                                    long elapsedMillis,
                                    List<String> warnings) {
    public OcRefreshSafetyResult {
        frontier = frontier == null ? List.of() : List.copyOf(frontier);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
