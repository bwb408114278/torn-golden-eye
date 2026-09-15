package pn.torn.goldeneye.torn.service.racing.query;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;

import java.util.List;
import java.util.Random;

/**
 * PC赛车抽奖计算器。
 *
 * <p>种子由赛事ID与固定盐派生，{@link String#hashCode()} 由JLS规定，跨JVM稳定，
 * 因此同一赛事的抽奖结果可被任何人在任何时候复现。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Component
public class PcRaceDrawCalculator {
    /**
     * 在未撞车选手中按赛事ID与固定盐做确定性抽取。
     *
     * @param raceId 赛事ID
     * @param pool   未撞车的全部联盟选手（调用方保证顺序稳定）
     * @return 中奖选手；池为空时返回null
     */
    public PcRaceParticipantVO draw(long raceId, List<PcRaceParticipantVO> pool) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }

        Random random = new Random((raceId + RacingConstants.DRAW_SEED_SUFFIX).hashCode());
        return pool.get(random.nextInt(pool.size()));
    }
}
