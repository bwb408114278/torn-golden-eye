package pn.torn.goldeneye.repository.dao.faction.oc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.model.faction.oc.OcPlanningRewardStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.OcRankNameKey;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcRewardEvidenceCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC规划收益统计Mapper测试。使用测试专属帮派命名空间并在结束后物理删除数据。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("OC规划收益统计Mapper")
class OcPlanningRewardStatsMapperTest {
    private static final long TEST_FACTION = 999_901L;
    private static final int TEST_RANK = 61;
    private static final String COMPLETE_OC = "RTStats完整样本";
    private static final String PARTIAL_OC = "RTStats缺失样本";

    @Autowired
    private TornFactionOcDAO ocDao;

    private final OcRewardEvidenceCalculator calculator = new OcRewardEvidenceCalculator();
    private final List<Long> insertedIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!insertedIds.isEmpty()) {
            ocDao.deleteByIdList(insertedIds);
            insertedIds.clear();
        }
    }

    @Test
    @DisplayName("应按OC实例口径聚合完整奖励且不把非法样本计入完整数")
    void shouldAggregatePerInstanceRewardWithCompletenessRule() {
        insertOc(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, 1000L, "200#300");
        insertOc(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, 500L, "100");
        insertOc(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, 700L, "abc");
        insertOc(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, null, "50");
        insertOc(TornOcStatusEnum.FAILURE, COMPLETE_OC, 900L, "100");

        Map<String, OcPlanningRewardStatsDO> stats = calculator.aggregate(
                ocDao.queryCompletedByOcKeys(List.of(rankName(COMPLETE_OC))));

        OcPlanningRewardStatsDO complete = stats.get(ocKey(COMPLETE_OC));
        assertEquals(5, complete.attemptCount());
        assertEquals(4, complete.successCount());
        assertEquals(2, complete.rewardCompleteCount());
        assertEquals(2100L, complete.totalReward());
        assertEquals(420.0, complete.observedRewardPerAttempt().doubleValue(), 0.001);
        assertEquals(Long.valueOf(600L), complete.rewardFloor());
    }

    @Test
    @DisplayName("应仅读取目标档案范围且不包含未完成状态记录")
    void shouldReadOnlyTargetScopeAndCompletedStatus() {
        insertOc(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, 100L, "50");
        insertOc(TornOcStatusEnum.RECRUITING, COMPLETE_OC, 100L, "50");

        List<TornFactionOcDO> rows = ocDao.queryCompletedByOcKeys(
                List.of(rankName(COMPLETE_OC)));

        assertEquals(1, rows.size());
        assertEquals(COMPLETE_OC, rows.getFirst().getName());
    }

    @Test
    @DisplayName("空奖励物品值按0处理且负数样本不计入完整样本")
    void shouldTreatBlankItemsAsZeroAndRejectNegativeValues() {
        TornFactionOcDO blankItems = row(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC,
                100L, "");
        assertEquals(100L, calculator.parseCompleteReward(blankItems).orElse(-1));

        TornFactionOcDO negative = row(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC,
                100L, "-50");
        assertFalse(calculator.parseCompleteReward(negative).isPresent());

        TornFactionOcDO missing = row(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC,
                null, "50");
        assertFalse(calculator.parseCompleteReward(missing).isPresent());
    }

    @Test
    @DisplayName("查询应单批执行而不出现循环单OC查询")
    void shouldQueryEnabledScopeInSingleBatch() {
        insertOc(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, 100L, "50");
        insertOc(TornOcStatusEnum.SUCCESSFUL, PARTIAL_OC, 200L, "50");

        List<TornFactionOcDO> rows = ocDao.queryCompletedByOcKeys(
                List.of(rankName(COMPLETE_OC), rankName(PARTIAL_OC)));

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(oc -> COMPLETE_OC.equals(oc.getName())));
        assertTrue(rows.stream().anyMatch(oc -> PARTIAL_OC.equals(oc.getName())));
    }

    private void insertOc(TornOcStatusEnum status, String name, Long rewardMoney,
                          String rewardItemsValue) {
        TornFactionOcDO oc = row(status, name, rewardMoney, rewardItemsValue);
        ocDao.save(oc);
        insertedIds.add(oc.getId());
    }

    private TornFactionOcDO row(TornOcStatusEnum status, String name, Long rewardMoney,
                                String rewardItemsValue) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setFactionId(TEST_FACTION);
        oc.setName(name);
        oc.setRank(TEST_RANK);
        oc.setStatus(status.getCode());
        oc.setRewardMoney(rewardMoney);
        oc.setRewardItems(rewardItemsValue == null ? "" : "item");
        oc.setRewardItemsValue(rewardItemsValue);
        return oc;
    }

    @Test
    @DisplayName("等级名称成对条件应精确匹配且不受同名不同等级记录干扰")
    void shouldMatchExactRankNamePairWithoutSameNameCrossRankNoise() {
        insertOc(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, 100L, "50");
        TornFactionOcDO otherRank = row(TornOcStatusEnum.SUCCESSFUL, COMPLETE_OC, 900L, "50");
        otherRank.setRank(TEST_RANK + 1);
        ocDao.save(otherRank);
        insertedIds.add(otherRank.getId());

        List<TornFactionOcDO> rows = ocDao.queryCompletedByOcKeys(
                List.of(rankName(COMPLETE_OC)));

        assertEquals(1, rows.size());
        assertEquals(TEST_RANK, rows.getFirst().getRank());
    }

    private String ocKey(String name) {
        return TEST_RANK + ":" + name;
    }

    private OcRankNameKey rankName(String name) {
        return new OcRankNameKey(TEST_RANK, name);
    }
}
