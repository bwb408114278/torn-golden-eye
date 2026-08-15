package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;

import java.util.List;
import java.util.Map;

/**
 * 刷新向量搜索端口。时间线规划引擎通过该最小不可变契约委托向量搜索，
 * 由搜索子包的实现装配，避免时间线包反向依赖搜索实现。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public interface OcVectorSearchPort {

    /**
     * 按总刷新次数递增搜索全部普通池和高阶池向量。
     *
     * @param request            求解请求
     * @param evidenceByTemplate 按模板键索引的价值证据
     * @param deadline           求解截止纳秒时间
     * @return 向量搜索结果
     */
    OcVectorSearchOutcome search(OcRefreshSafetyRequest request,
                                 Map<String, OcValueEvidence> evidenceByTemplate,
                                 long deadline);

    /**
     * 判断已证明安全的候选是否触及单池搜索上限。
     *
     * @param candidates 已证明安全的候选集合
     * @return 任一池次数达到搜索上限时返回true
     */
    boolean touchesSearchLimit(List<SafeCandidate> candidates);

    /**
     * 向量搜索的最小不可变结果契约。
     *
     * @param candidates      已证明安全的候选集合
     * @param timedOut        是否达到时间预算
     * @param budgetExhausted 是否达到组合评估预算
     */
    record OcVectorSearchOutcome(
            List<SafeCandidate> candidates,
            boolean timedOut,
            boolean budgetExhausted) {
    }
}
