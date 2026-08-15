package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间线搜索状态裁剪器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("时间线搜索状态裁剪")
class OcTimelineStatePrunerTest {
    private final OcTimelineStatePruner pruner = new OcTimelineStatePruner();

    /**
     * 测试用状态元组。
     */
    private record State(int scheduled, int anchors, long pauseNanos, long availabilitySum) {
    }

    @Test
    @DisplayName("应移除被支配的状态")
    void shouldRemoveDominatedStates() {
        State dominated = new State(2, 1, 100, 50);
        State dominant = new State(3, 2, 100, 50);

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(
                List.of(dominated, dominant),
                State::scheduled, State::anchors, State::pauseNanos,
                State::availabilitySum);

        assertEquals(List.of(dominant), result.kept());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("互不支配的状态应全部保留")
    void shouldKeepAllNonDominatedStates() {
        State moreScheduled = new State(3, 1, 100, 80);
        State earlierRelease = new State(2, 1, 100, 50);

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(
                List.of(moreScheduled, earlierRelease),
                State::scheduled, State::anchors, State::pauseNanos,
                State::availabilitySum);

        assertEquals(2, result.kept().size());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("状态数量超过上限时应截断并暴露截断标记")
    void shouldTruncateAndExposeTruncationWhenStatesExceedLimit() {
        List<State> states = java.util.stream.IntStream.rangeClosed(1, pruner.maxActiveStates() + 10)
                .mapToObj(index -> new State(index, index, index, index))
                .toList();

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(states,
                State::scheduled, State::anchors,
                State::pauseNanos, State::availabilitySum);

        assertTrue(result.kept().size() <= pruner.maxActiveStates());
        assertTrue(result.truncated());
    }
}
