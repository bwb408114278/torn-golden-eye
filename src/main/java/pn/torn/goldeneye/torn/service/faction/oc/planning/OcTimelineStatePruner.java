package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 时间线搜索状态裁剪器。按已排程数量、价值、成员可用性、停转和锚点做支配剪枝，
 * 并限制同时展开的状态数，防止单节点排列的阶乘搜索。
 *
 * <p>预算截断由调用方记录为{@code UNPROVEN_SEARCH_BUDGET}，
 * 剪枝本身不改变已证明安全向量的语义。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Component
public class OcTimelineStatePruner {
    private static final int MAX_ACTIVE_STATES = 16;

    /**
     * 保留非支配状态并限制状态数量。当且仅当某状态在全部维度不差且至少一维更优时支配另一状态。
     *
     * @param states 当前候选状态列表，按探索顺序排列
     * @return 裁剪后的状态列表，保持稳定顺序
     */
    public <T> List<T> prune(List<T> states, java.util.function.ToLongFunction<T> scheduledCount,
                             java.util.function.ToLongFunction<T> anchorCount,
                             java.util.function.ToLongFunction<T> pauseNanos,
                             java.util.function.ToLongFunction<T> availabilitySum) {
        List<T> kept = new java.util.ArrayList<>();
        boolean truncated = false;
        for (T state : states) {
            if (kept.size() >= MAX_ACTIVE_STATES) {
                truncated = true;
                break;
            }
            boolean dominated = kept.stream().anyMatch(other ->
                    scheduledCount.applyAsLong(other) >= scheduledCount.applyAsLong(state)
                            && anchorCount.applyAsLong(other) >= anchorCount.applyAsLong(state)
                            && pauseNanos.applyAsLong(other) <= pauseNanos.applyAsLong(state)
                            && availabilitySum.applyAsLong(other) <= availabilitySum.applyAsLong(state)
                            && (scheduledCount.applyAsLong(other) > scheduledCount.applyAsLong(state)
                            || anchorCount.applyAsLong(other) > anchorCount.applyAsLong(state)
                            || pauseNanos.applyAsLong(other) < pauseNanos.applyAsLong(state)
                            || availabilitySum.applyAsLong(other) < availabilitySum.applyAsLong(state)));
            if (!dominated) {
                kept.removeIf(other ->
                        scheduledCount.applyAsLong(state) >= scheduledCount.applyAsLong(other)
                                && anchorCount.applyAsLong(state) >= anchorCount.applyAsLong(other)
                                && pauseNanos.applyAsLong(state) <= pauseNanos.applyAsLong(other)
                                && availabilitySum.applyAsLong(state) <= availabilitySum.applyAsLong(other)
                                && (scheduledCount.applyAsLong(state) > scheduledCount.applyAsLong(other)
                                || anchorCount.applyAsLong(state) > anchorCount.applyAsLong(other)
                                || pauseNanos.applyAsLong(state) < pauseNanos.applyAsLong(other)
                                || availabilitySum.applyAsLong(state) < availabilitySum.applyAsLong(other)));
                kept.add(state);
            }
        }
        if (truncated) {
            kept.add(states.getLast());
        }
        return kept;
    }

    /**
     * 获取同时展开的状态上限。
     *
     * @return 状态上限
     */
    public int maxActiveStates() {
        return MAX_ACTIVE_STATES;
    }
}
