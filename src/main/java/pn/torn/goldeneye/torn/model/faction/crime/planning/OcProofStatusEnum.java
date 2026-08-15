package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * OC时间线求解证明状态。只描述证明维度，不与配置状态混用。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public enum OcProofStatusEnum {
    /**
     * 已证明安全：全部允许随机组合均存在满足硬约束的完整时间线。
     */
    PROVEN_SAFE,
    /**
     * 已证明不可行：有限证明窗口内存在确定性矛盾或完整无截断检查证明无解。
     */
    PROVEN_INFEASIBLE,
    /**
     * 搜索达到时间预算，仅返回已证明安全下界。
     */
    UNPROVEN_TIMEOUT,
    /**
     * 搜索达到状态或节点预算，仅返回已证明安全下界。
     */
    UNPROVEN_SEARCH_BUDGET,
    /**
     * 确定性顺序或启发式未找到可行时间线，不代表无解。
     */
    UNPROVEN_HEURISTIC_MISS,
    /**
     * 未参与求解（例如配置无效时直接跳过）。
     */
    NOT_EVALUATED
}
