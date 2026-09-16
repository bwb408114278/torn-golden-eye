package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwRankDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * RW贡献榜名次回放结算金标准测试。
 *
 * <p>对真实历史真赛48522重放结算，断言战神榜口径的名次与基础分完全一致：
 * Cinderine第1名、NoZuoNoDie第9名（92×1.2=110.4）、全榜75行。
 * 该用例是名次口径的唯一金标准证据，事务回滚保证共享库零残留。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("RW贡献榜名次回放结算金标准测试")
class RwContributionSettleReplayItTest {
    private static final long RW_ID = 48522L;
    private static final long CINDERINE = 1864837L;
    private static final long NO_ZUO_NO_DIE = 3312605L;
    private static final int EXPECTED_ROW_COUNT = 75;
    private static final int CINDERINE_RANK = 1;
    private static final int NO_ZUO_NO_DIE_RANK = 9;
    private static final int NO_ZUO_NO_DIE_OPPONENT_SCORE = 22625;

    @Autowired
    private TornFactionRwDAO rwDao;
    @Autowired
    private TornFactionRwRankDAO rankDao;
    @Autowired
    private RwRankSettleService settleService;
    @Autowired
    private RwContributionCalculator calculator;

    @Test
    @DisplayName("48522重放结算得到Cinderine第1名与NoZuoNoDie第9名共75行")
    void settle_realWar_replaysFrozenGodRank() {
        TornFactionRwDO rw = rwDao.getById(RW_ID);
        assertNotNull(rw, "金标准依赖本地已同步的真实真赛48522，请确认测试库可用");

        settleService.settle(rw);

        List<TornFactionRwRankDO> rankList = rankDao.lambdaQuery()
                .eq(TornFactionRwRankDO::getRwId, RW_ID)
                .list();
        assertEquals(EXPECTED_ROW_COUNT, rankList.size(), "48522战神榜应为75行");
        assertEquals(CINDERINE_RANK, rankOf(rankList, CINDERINE), "Cinderine应为第1名");
        assertEquals(NO_ZUO_NO_DIE_RANK, rankOf(rankList, NO_ZUO_NO_DIE), "NoZuoNoDie应为第9名");
        assertEquals(92, calculator.baseScore(NO_ZUO_NO_DIE_RANK), "第9名基础分应为92");
        assertEquals(0, new BigDecimal("110.4").compareTo(
                        calculator.warScore(NO_ZUO_NO_DIE_RANK, NO_ZUO_NO_DIE_OPPONENT_SCORE)),
                "第9名在对手22625分时应得到110.4");
    }

    @Test
    @DisplayName("重复结算同一场次不残留历史行并保持结果一致")
    void settle_sameWarTwice_physicallyReplacesRows() {
        TornFactionRwDO rw = rwDao.getById(RW_ID);
        assertNotNull(rw, "金标准依赖本地已同步的真实真赛48522，请确认测试库可用");

        settleService.settle(rw);
        settleService.settle(rw);

        List<TornFactionRwRankDO> rankList = rankDao.lambdaQuery()
                .eq(TornFactionRwRankDO::getRwId, RW_ID)
                .list();
        assertEquals(EXPECTED_ROW_COUNT, rankList.size(), "重结算必须物理删除旧行后重插");
        assertEquals(NO_ZUO_NO_DIE_RANK, rankOf(rankList, NO_ZUO_NO_DIE), "重结算名次必须保持一致");
    }

    private int rankOf(List<TornFactionRwRankDO> rankList, long userId) {
        return rankList.stream()
                .filter(rank -> rank.getUserId() == userId)
                .findFirst()
                .map(TornFactionRwRankDO::getRankNum)
                .orElseThrow(() -> new AssertionError("结算行缺少用户" + userId));
    }
}
