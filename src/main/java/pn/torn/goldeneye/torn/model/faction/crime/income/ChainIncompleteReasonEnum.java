package pn.torn.goldeneye.torn.model.faction.crime.income;

/**
 * 链回溯不完整原因。
 *
 * <p>用于标识{@code previousOcId}链在回溯时无法形成完整链的具体原因，
 * 由批量门面与事务Worker据此输出明确的异常统计与日志。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.04
 */
public enum ChainIncompleteReasonEnum {
    /**
     * 期望的祖先节点不存在或已被逻辑删除。
     */
    MISSING_ANCESTOR,
    /**
     * 链中存在环形引用，无法得到确定的线性祖先关系。
     */
    CYCLE,
    /**
     * 祖先节点与叶子不属于同一帮派。
     */
    FACTION_MISMATCH
}
