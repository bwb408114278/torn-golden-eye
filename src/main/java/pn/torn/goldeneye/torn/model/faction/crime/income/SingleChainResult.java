package pn.torn.goldeneye.torn.model.faction.crime.income;

import java.util.List;
import java.util.Set;

/**
 * 单链事务处理结果。
 *
 * <p>携带叶子与整链信息，便于批量门面统计与日志输出；异常部分income场景下
 * {@code existingIncomeOcIds}列出链中已有income的节点；祖先缺失场景下
 * {@code missingAncestorOcId}给出第一个缺失祖先的OC ID。</p>
 *
 * @param outcome             处理结果类型
 * @param leafOcId            叶子OC ID
 * @param leafOcName          叶子OC名称
 * @param chainOcIds          整条链（含叶子自身）的OC ID列表，从叶子向根回溯
 * @param existingIncomeOcIds 链中已存在income的OC ID集合，无则为空集合
 * @param missingAncestorOcId 链不完整时第一个缺失祖先的OC ID；链完整时为{@code null}
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
public record SingleChainResult(
        SingleChainOutcomeEnum outcome,
        Long leafOcId,
        String leafOcName,
        List<Long> chainOcIds,
        Set<Long> existingIncomeOcIds,
        Long missingAncestorOcId) {

    /**
     * 构造不含缺失祖先信息的单链处理结果。
     *
     * @param outcome             处理结果类型
     * @param leafOcId            叶子OC ID
     * @param leafOcName          叶子OC名称
     * @param chainOcIds          整条链OC ID列表
     * @param existingIncomeOcIds 链中已存在income的OC ID集合
     */
    public SingleChainResult(SingleChainOutcomeEnum outcome, Long leafOcId, String leafOcName,
                             List<Long> chainOcIds, Set<Long> existingIncomeOcIds) {
        this(outcome, leafOcId, leafOcName, chainOcIds, existingIncomeOcIds, null);
    }
}
