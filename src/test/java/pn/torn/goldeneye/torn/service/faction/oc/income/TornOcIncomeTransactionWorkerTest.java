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
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.BatchIncomeResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
class TornOcIncomeTransactionWorkerTest {
    @Autowired
    private TornOcBatchIncomeService batchIncomeService;
    @Autowired
    private TornFactionOcDAO ocDao;
    @Autowired
    private TornFactionOcSlotDAO slotDao;
    @Autowired
    private TornFactionOcIncomeDAO incomeDao;
    @Autowired
    private TornFactionOcIncomeSummaryDAO incomeSummaryDao;
    @MockitoSpyBean
    private TornOcIncomeService incomeService;

    private static final Long FACTION_ID = 999003L;
    private static final Long USER_ID = 888003L;

    private List<String> originalRotationList;

    @BeforeEach
    void setUp() {
        originalRotationList = TornConstants.ROTATION_OC_NAME.get(FACTION_ID);
        TornConstants.ROTATION_OC_NAME.put(FACTION_ID, List.of(
                TornConstants.OC_NAME_STACKING_THE_DECK, TornConstants.OC_NAME_ACE_IN_THE_HOLE));
        cleanupFactionData();
    }

    @AfterEach
    void cleanup() {
        cleanupFactionData();
        if (originalRotationList == null) {
            TornConstants.ROTATION_OC_NAME.remove(FACTION_ID);
        } else {
            TornConstants.ROTATION_OC_NAME.put(FACTION_ID, originalRotationList);
        }
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID);
        Mockito.reset(incomeService);
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
        // 在income明细写入后、汇总阶段抛出异常，模拟汇总写入失败
        doThrow(new RuntimeException("注入的汇总写入失败"))
                .when(incomeService).calcMonthlyIncomeSummary(anyLong(), anyString());

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
        slotDao.lambdaUpdate()
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

    /**
     * 创建两步链 Stacking the Deck -> Ace in the Hole，各一步骤一个成员。
     *
     * @return 链上两个OC，index 0为根节点，index 1为叶子
     */
    private TornFactionOcDO[] createTwoStepChain() {
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 1000000L);
        createSlot(step1.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(step2.getId(), USER_ID, "Imitator#1", 70, 30000L);
        return new TornFactionOcDO[]{step1, step2};
    }

    private void insertSummary(Long userId, String yearMonth) {
        TornFactionOcIncomeSummaryDO summary = new TornFactionOcIncomeSummaryDO();
        summary.setUserId(userId);
        summary.setFactionId(FACTION_ID);
        summary.setYearMonth(yearMonth);
        summary.setIsSettled(false);
        summary.setTotalEffectiveHours(BigDecimal.ZERO);
        summary.setTotalItemCost(0L);
        summary.setTotalReward(0L);
        summary.setNetReward(0L);
        summary.setFinalIncome(0L);
        summary.setOcCount(0);
        summary.setSuccessOcCount(0);
        incomeSummaryDao.save(summary);
    }

    private long countIncome(Long ocId1, Long ocId2) {
        return incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .in(TornFactionOcIncomeDO::getOcId, List.of(ocId1, ocId2))
                .count();
    }

    private long countSummary() {
        return incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getFactionId, FACTION_ID)
                .count();
    }

    /**
     * 通过持久层逻辑删除测试帮派的全部测试数据（合成帮派，不触碰正式数据）。
     */
    private void cleanupFactionData() {
        List<Long> ocIds = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, FACTION_ID)
                .list()
                .stream()
                .map(TornFactionOcDO::getId)
                .toList();
        incomeDao.lambdaUpdate().eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID).remove();
        incomeSummaryDao.lambdaUpdate().eq(TornFactionOcIncomeSummaryDO::getFactionId, FACTION_ID).remove();
        if (!ocIds.isEmpty()) {
            slotDao.lambdaUpdate().in(TornFactionOcSlotDO::getOcId, ocIds).remove();
        }
        ocDao.lambdaUpdate().eq(TornFactionOcDO::getFactionId, FACTION_ID).remove();
    }

    private TornFactionOcDO createOc(Long previousOcId, String name, Integer rank,
                                     TornOcStatusEnum status, LocalDateTime executedTime, Long rewardMoney) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setFactionId(FACTION_ID);
        oc.setPreviousOcId(previousOcId);
        oc.setName(name);
        oc.setRank(rank);
        oc.setStatus(status.getCode());
        oc.setExecutedTime(executedTime);
        oc.setRewardMoney(rewardMoney);
        ocDao.save(oc);
        return oc;
    }

    private TornFactionOcSlotDO createSlot(Long ocId, Long userId, String position, Integer passRate, Long itemValue) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        slot.setPassRate(passRate);
        slot.setOutcomeItemValue(itemValue);
        slotDao.save(slot);
        return slot;
    }
}
