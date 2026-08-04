package pn.torn.goldeneye.torn.model.faction.crime.income;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;

import java.util.List;

/**
 * 链回溯结果。
 *
 * <p>封装{@code previousOcId}链回溯的产物。当链完整时返回从最早祖先到叶子的完整节点列表；
 * 当链不完整时返回已加载的部分节点、缺失祖先ID与具体原因，由调用方fail-closed处理。</p>
 *
 * @param chain               完整时按从最早祖先到叶子的顺序返回；不完整时返回已加载的部分节点（含叶子）
 * @param complete            链是否完整
 * @param missingAncestorOcId 链不完整时第一个缺失祖先的OC ID；链完整时为{@code null}
 * @param reason              链不完整的原因；链完整时为{@code null}
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.04
 */
public record ChainLoadResult(
        List<TornFactionOcDO> chain,
        boolean complete,
        Long missingAncestorOcId,
        ChainIncompleteReasonEnum reason) {
}
