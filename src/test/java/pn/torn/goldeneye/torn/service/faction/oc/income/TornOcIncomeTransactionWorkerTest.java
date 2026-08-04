package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.BatchIncomeResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 单链事务原子回滚测试。
 *
 * <p>通过真实数据库约束故障注入验证：链明细或汇总任一环节失败，整条链的income与summary
 * 在REQUIRES_NEW事务中全部回滚，不会留下部分提交；故障清除后重试同一链成功。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
@SpringBootTest
@DisplayName("单链事务原子回滚测试")
class TornOcIncomeTransactionWorkerTest extends TornOcIncomeDbTestSupport {
    @Autowired
    private TornOcBatchIncomeService batchIncomeService;
    @MockitoSpyBean
    private TornFactionOcIncomeSummaryDAO incomeSummaryDao;
    @MockitoSpyBean
    private TornOcIncomeService incomeService;

    private static final Long FACTION_ID = 999003L;
    private static final Long USER_ID = 888003L;
    private static final Long USER_ID_2 = 888004L;

    private List<String> originalRotationList;

    @BeforeEach
    void setUp() {
        originalRotationList = saveRotationList(FACTION_ID, List.of(
                TornConstants.OC_NAME_STACKING_THE_DECK, TornConstants.OC_NAME_ACE_IN_THE_HOLE,
                TornConstants.OC_NAME_MANIFEST_CRUELTY, TornConstants.OC_NAME_GONE_FISSION,
                TornConstants.OC_NAME_CRANE_REACTION));
        // 三段链测试使用的非生产rank/岗位无全局系数，测试插入全局系数保证R11下不因0工时误判失败
        insertCoefficient(0L, TornConstants.OC_NAME_MANIFEST_CRUELTY, 8, "Hacker#1", 65);
        insertCoefficient(0L, TornConstants.OC_NAME_GONE_FISSION, 9, "Imitator#1", 70);
        insertCoefficient(0L, TornConstants.OC_NAME_CRANE_REACTION, 10, "Muscle#1", 75);
        coefficientManager.refreshCache();
        cleanupFactionData();
    }

    @AfterEach
    void cleanup() {
        cleanupFactionData();
        cleanupConfigsAndRefreshCache();
        restoreRotationList(FACTION_ID, originalRotationList);
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID);
        Mockito.reset(incomeService, incomeSummaryDao);
    }

    @Test
    @DisplayName("正常链路生成整链明细与汇总")
    void chain_success_generatesIncomeAndSummary() {
        TornFactionOcDO[] chain = createTwoStepChain();
        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.successCount());
        assertEquals(2, countIncome(chain[0].getId(), chain[1].getId()));
        assertEquals(1L, countSummary());
    }

    @Test
    @DisplayName("第一节点写入后失败：整链income与summary全部回滚")
    void incomeWriteFailure_afterFirstNode_rollsBackWholeChain() {
        // 第二步骤岗位用户为空，导致第二节点income写入触发NOT NULL约束，第一节点已写入的数据必须回滚
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 1000000L);
        createSlot(step1.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(step2.getId(), null, "Imitator#1", 70, 30000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.failureCount());
        assertEquals(0, countIncome(step1.getId(), step2.getId()));
        assertEquals(0L, countSummary());
    }

    @Test
    @DisplayName("最终节点写入后、汇总阶段失败：整链income回滚")
    void summaryQueryFailure_duplicateSummary_rollsBackWholeChain() {
        TornFactionOcDO[] chain = createTwoStepChain();
        // 预置同一结算键的两条汇总，使汇总查询one()触发TooManyResults，发生在income写入之后
        insertSummary(USER_ID, "2026-04");
        insertSummary(USER_ID, "2026-04");

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.failureCount());
        assertEquals(0, countIncome(chain[0].getId(), chain[1].getId()));
        assertEquals(2L, countSummary());
    }

    @Test
    @DisplayName("汇总写入失败：整链income与summary全部回滚")
    void summaryWriteFailure_rollsBackWholeChain() {
        TornFactionOcDO[] chain = createTwoStepChain();
        // 在income明细写入后、受影响月份汇总重算阶段抛出异常，模拟汇总写入失败
        doThrow(new RuntimeException("注入的汇总写入失败"))
                .when(incomeService).recalcAffectedMonths(anyLong(), anyList());

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.failureCount());
        assertEquals(0, countIncome(chain[0].getId(), chain[1].getId()));
        assertEquals(0L, countSummary());
    }

    @Test
    @DisplayName("修复故障后重试同一链成功，不残留部分数据")
    void faultCleared_retrySucceeds() {
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 1000000L);
        createSlot(step1.getId(), USER_ID, "Hacker#1", 65, 50000L);
        TornFactionOcSlotDO brokenSlot = createSlot(step2.getId(), null, "Imitator#1", 70, 30000L);

        BatchIncomeResult first = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));
        assertNotNull(first);
        assertEquals(1, first.failureCount());
        assertEquals(0, countIncome(step1.getId(), step2.getId()));

        // 修复第二步骤岗位用户后重试同一链
        ocSlotDao.lambdaUpdate()
                .set(TornFactionOcSlotDO::getUserId, USER_ID)
                .eq(TornFactionOcSlotDO::getId, brokenSlot.getId())
                .update();

        BatchIncomeResult second = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));
        assertNotNull(second);
        assertEquals(1, second.successCount());
        assertEquals(2, countIncome(step1.getId(), step2.getId()));
        assertEquals(1L, countSummary());
    }

    @Test
    @DisplayName("真实写入入口故障注入：第二节点明细写入时抛异常，整链回滚")
    void incomeWriteFailure_injectedAtSecondNodeWriteEntry_rollsBackWholeChain() {
        // 在第二个链节点实际写入入口注入异常，不依赖已完成岗位用户为空的弱夹具
        TornFactionOcDO[] chain = createTwoStepChain();
        doThrow(new RuntimeException("注入的第二节点明细写入失败"))
                .when(incomeService).saveIncomeRecords(argThat(oc -> chain[1].getId().equals(oc.getId())),
                        any(), anyBoolean(), anyLong(), anyLong());

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.failureCount());
        assertEquals(0, countIncome(chain[0].getId(), chain[1].getId()));
        assertEquals(0L, countSummary());
    }

    @Test
    @DisplayName("多用户summary第一条真实写入后第二条失败：首条修改与整链income全部回滚")
    void multiUserSummary_secondWriteFailure_rollsBackFirstSummaryAndIncome() {
        // 链上两个节点分别由不同用户参与，汇总阶段会为两个用户分别写summary
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 1000000L);
        createSlot(step1.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(step2.getId(), USER_ID_2, "Imitator#1", 70, 30000L);

        // 第一次真实写入后，让第二条save/updateById失败
        AtomicInteger summaryWrites = new AtomicInteger();
        doAnswer(invocation -> {
            if (summaryWrites.incrementAndGet() == 2) {
                throw new RuntimeException("注入的第二条summary写入失败");
            }
            return invocation.callRealMethod();
        }).when(incomeSummaryDao).save(any());
        doAnswer(invocation -> {
            if (summaryWrites.incrementAndGet() == 2) {
                throw new RuntimeException("注入的第二条summary写入失败");
            }
            return invocation.callRealMethod();
        }).when(incomeSummaryDao).updateById(any());

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.failureCount());
        assertEquals(0, countIncome(step1.getId(), step2.getId()));
        // 第一条summary的写入也随整链回滚，不残留
        assertEquals(0L, countSummary());
    }

    @Test
    @DisplayName("Manifest Cruelty→Gone Fission→Crane Reaction三段链完整结算")
    void threeSegmentChain_fullSettlement() {
        TornFactionOcDO root = createOc(null, TornConstants.OC_NAME_MANIFEST_CRUELTY, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO mid = createOc(root.getId(), TornConstants.OC_NAME_GONE_FISSION, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 0L);
        TornFactionOcDO leaf = createOc(mid.getId(), TornConstants.OC_NAME_CRANE_REACTION, 10,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 3, 10, 0), 2000000L);
        createSlot(root.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(mid.getId(), USER_ID, "Imitator#1", 70, 30000L);
        createSlot(leaf.getId(), USER_ID_2, "Muscle#1", 75, 20000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.successCount());
        // 三个节点都生成明细
        assertEquals(3, countIncome(root.getId(), mid.getId(), leaf.getId()));
        // 最终叶子奖励只计一次，总奖励为叶子奖励
        TornFactionOcIncomeSummaryDO summary = querySummary(USER_ID, "2026-04");
        assertNotNull(summary);
        assertEquals(2000000L, summary.getTotalReward());
    }

    @Test
    @DisplayName("三段链中间节点已有部分income时识别为异常，不新增明细")
    void threeSegmentChain_middleNodePartialIncome_abnormal() {
        TornFactionOcDO root = createOc(null, TornConstants.OC_NAME_MANIFEST_CRUELTY, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO mid = createOc(root.getId(), TornConstants.OC_NAME_GONE_FISSION, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 0L);
        TornFactionOcDO leaf = createOc(mid.getId(), TornConstants.OC_NAME_CRANE_REACTION, 10,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 3, 10, 0), 2000000L);
        createSlot(root.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(mid.getId(), USER_ID, "Imitator#1", 70, 30000L);
        createSlot(leaf.getId(), USER_ID_2, "Muscle#1", 75, 20000L);

        // 中间节点已写入income，叶子无income → 整链识别为异常部分income
        insertIncome(mid, USER_ID, "Imitator#1", true, 2000000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.abnormalPartialIncomeCount());
        assertEquals(0, result.successCount());
        // 不新增任何明细
        assertEquals(1, countIncome(root.getId(), mid.getId(), leaf.getId()));
    }

    /**
     * 创建两步链 Stacking the Deck -> Ace in the Hole，各一步骤一个成员。
     *
     * @return 链上两个OC，index 0为根节点，index 1为叶子
     */
    private TornFactionOcDO[] createTwoStepChain() {
        TornFactionOcDO step1 = createOc(FACTION_ID, null, TornConstants.OC_NAME_STACKING_THE_DECK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(FACTION_ID, step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 1000000L);
        createSlot(step1.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(step2.getId(), USER_ID, "Imitator#1", 70, 30000L);
        return new TornFactionOcDO[]{step1, step2};
    }

    private void insertSummary(Long userId, String yearMonth) {
        super.insertSummary(userId, FACTION_ID, yearMonth);
    }

    private long countIncome(Long ocId1, Long ocId2) {
        return incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .in(TornFactionOcIncomeDO::getOcId, List.of(ocId1, ocId2))
                .count();
    }

    private long countIncome(Long ocId1, Long ocId2, Long ocId3) {
        return incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .in(TornFactionOcIncomeDO::getOcId, List.of(ocId1, ocId2, ocId3))
                .count();
    }

    private TornFactionOcIncomeSummaryDO querySummary(Long userId, String yearMonth) {
        return incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getUserId, userId)
                .eq(TornFactionOcIncomeSummaryDO::getFactionId, FACTION_ID)
                .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                .one();
    }

    private long countSummary() {
        return incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getFactionId, FACTION_ID)
                .count();
    }

    /**
     * 物理删除测试帮派的全部测试数据（合成帮派，不触碰正式数据）。
     */
    private void cleanupFactionData() {
        physicalDeleteFactionAllData(FACTION_ID);
    }

    private TornFactionOcDO createOc(Long previousOcId, String name, Integer rank,
                                     TornOcStatusEnum status, LocalDateTime executedTime, Long rewardMoney) {
        return createOc(FACTION_ID, previousOcId, name, rank, status, executedTime, rewardMoney);
    }
}
