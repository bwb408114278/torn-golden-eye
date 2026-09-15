package pn.torn.goldeneye.torn.service.racing.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PC赛车抽奖确定性测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@DisplayName("PC赛车抽奖计算测试")
class PcRaceDrawCalculatorTest {
    private static final long RACE_ID = 1000L;

    private final PcRaceDrawCalculator drawCalculator = new PcRaceDrawCalculator();

    @Test
    @DisplayName("同一赛事重复抽取结果恒定")
    void draw_shouldReturnSameWinnerForSameRace() {
        List<PcRaceParticipantVO> pool = pool(1L, 2L, 3L, 4L, 5L);

        PcRaceParticipantVO first = drawCalculator.draw(RACE_ID, pool);
        PcRaceParticipantVO second = new PcRaceDrawCalculator().draw(RACE_ID, pool);

        assertEquals(first.userId(), second.userId());
    }

    @Test
    @DisplayName("池顺序变化会改变抽取结果")
    void draw_shouldDependOnPoolOrder() {
        List<PcRaceParticipantVO> pool = pool(1L, 2L, 3L, 4L);
        List<PcRaceParticipantVO> reversedPool = new ArrayList<>(pool);
        Collections.reverse(reversedPool);

        PcRaceParticipantVO winner = drawCalculator.draw(RACE_ID, pool);
        PcRaceParticipantVO reversedWinner = drawCalculator.draw(RACE_ID, reversedPool);

        assertNotEquals(winner.userId(), reversedWinner.userId());
    }

    @Test
    @DisplayName("池为空时返回null")
    void draw_shouldReturnNullForEmptyPool() {
        assertNull(drawCalculator.draw(RACE_ID, List.of()));
        assertNull(drawCalculator.draw(RACE_ID, null));
    }

    private List<PcRaceParticipantVO> pool(long... userIds) {
        List<PcRaceParticipantVO> pool = new ArrayList<>(userIds.length);
        for (long userId : userIds) {
            pool.add(new PcRaceParticipantVO(userId, "选手" + userId, "PHN", null, null, null, null, false));
        }
        return pool;
    }
}
