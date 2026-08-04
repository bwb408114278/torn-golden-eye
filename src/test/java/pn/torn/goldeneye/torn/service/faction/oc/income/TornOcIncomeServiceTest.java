package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.base.exception.BizException;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大锅饭OC收益计算集成测试
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.04.20
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("大锅饭收入生成测试")
class TornOcIncomeServiceTest {
    @Autowired
    private TornOcIncomeService incomeService;
    @Autowired
    private TornFactionOcDAO ocDao;
    @Autowired
    private TornFactionOcSlotDAO ocSlotDao;
    @Autowired
    private TornFactionOcIncomeDAO incomeDao;
    @Autowired
    private TornFactionOcIncomeSummaryDAO incomeSummaryDao;

    private static final Long FACTION_ID = 1000L;
    private static final Long USER_ID_1 = 2001L;
    private static final Long USER_ID_2 = 2002L;

    @Test
    @DisplayName("单步OC计算")
    void testCalculateIncome_SingleOc() {
        // 单步OC，非链式
        TornFactionOcDO oc1 = createOc(null, TornConstants.OC_NAME_BREAK_THE_BANK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 1000000L, null);
        createSlot(oc1.getId(), USER_ID_1, "Thief#1", 60, 50000L);
        createSlot(oc1.getId(), USER_ID_2, "Thief#2", 70, 30000L);

        incomeService.calculateAndSaveIncome(oc1);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, oc1.getId())
                .list();

        assertEquals(2, incomes.size());
        assertTrue(incomes.stream().allMatch(TornFactionOcIncomeDO::getIsSuccess));
        assertEquals(1000000L, incomes.getFirst().getTotalReward());
        assertEquals(80000L, incomes.stream().mapToLong(TornFactionOcIncomeDO::getItemCost).sum());
    }

    @Test
    @DisplayName("单步OC计算, 收益为道具")
    void testCalculateIncome_SingleItemOc() {
        // 单步OC，非链式
        TornFactionOcDO oc1 = createOc(null, TornConstants.OC_NAME_WINDOW_OF_OPPORTUNITY, 7, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 0L,
                "400000#600000");
        createSlot(oc1.getId(), USER_ID_1, "Looter#1", 60, 50000L);
        createSlot(oc1.getId(), USER_ID_2, "Looter#2", 70, 30000L);

        incomeService.calculateAndSaveIncome(oc1);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, oc1.getId())
                .list();

        assertEquals(2, incomes.size());
        assertTrue(incomes.stream().allMatch(TornFactionOcIncomeDO::getIsSuccess));
        assertEquals(1000000L, incomes.getFirst().getTotalReward());
        assertEquals(80000L, incomes.stream().mapToLong(TornFactionOcIncomeDO::getItemCost).sum());
    }

    @Test
    @DisplayName("Chain OC同月计算")
    void testCalculateIncome_ChainOc_SameMonth() {
        // 链式OC，同月完成
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 0L, null);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 20, 15, 0), 2000000L, null);

        createSlot(step1.getId(), USER_ID_1, "Imitator#1", 80, 100000L);
        createSlot(step2.getId(), USER_ID_2, "Imitator#1", 75, 150000L);

        incomeService.calculateAndSaveIncome(step2);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .in(TornFactionOcIncomeDO::getOcId, List.of(step1.getId(), step2.getId()))
                .list();

        assertEquals(2, incomes.size());
        TornFactionOcIncomeDO income1 = incomes.stream()
                .filter(i -> i.getOcId().equals(step1.getId()))
                .findFirst().orElseThrow();
        assertEquals(TornConstants.OC_NAME_STACKING_THE_DECK, income1.getOcName());
        assertEquals(8, income1.getRank());
        assertEquals(USER_ID_1, income1.getUserId());

        TornFactionOcIncomeDO income2 = incomes.stream()
                .filter(i -> i.getOcId().equals(step2.getId()))
                .findFirst().orElseThrow();
        assertEquals(TornConstants.OC_NAME_ACE_IN_THE_HOLE, income2.getOcName());
        assertEquals(9, income2.getRank());
        assertEquals(USER_ID_2, income2.getUserId());

        assertTrue(incomes.stream().allMatch(i -> i.getTotalReward() == 2000000L));
        assertTrue(incomes.stream().allMatch(i -> i.getTotalItemCost() == 250000L));
    }

    @Test
    @DisplayName("Chain OC跨月计算")
    void testCalculateIncome_ChainOc_CrossMonth() {
        // 链式OC，跨月完成
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 3, 28, 10, 0), 0L, null);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 2, 15, 0), 1500000L, null);

        createSlot(step1.getId(), USER_ID_1, "Imitator#1", 80, 80000L);
        createSlot(step2.getId(), USER_ID_1, "Imitator#1", 75, 120000L);

        incomeService.calculateAndSaveIncome(step2);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .in(TornFactionOcIncomeDO::getOcId, List.of(step1.getId(), step2.getId()))
                .list();
        assertEquals(2, incomes.size());
        // 执行时间应该不一样
        assertFalse(incomes.stream().allMatch(i ->
                i.getOcExecutedTime().equals(LocalDateTime.of(2026, 4, 2, 15, 0))));

        // 收益应该都在4月
        List<TornFactionOcIncomeSummaryDO> summaryList = incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getUserId, USER_ID_1)
                .list();
        assertEquals(1, summaryList.size());
        assertEquals("2026-04", summaryList.getFirst().getYearMonth());
    }

    @Test
    @DisplayName("Chain OC第一步失败")
    void testCalculateIncome_FirstStepFailed() {
        // 第一步失败
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8, TornOcStatusEnum.FAILURE,
                LocalDateTime.of(2026, 4, 15, 10, 0), 0L, null);

        createSlot(step1.getId(), USER_ID_1, "Hacker#1", 60, 50000L);

        incomeService.calculateAndSaveIncome(step1);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, step1.getId())
                .list();

        assertEquals(1, incomes.size());
        assertFalse(incomes.getFirst().getIsSuccess());
        assertEquals(0L, incomes.getFirst().getTotalReward());
    }

    @Test
    @DisplayName("同月两个单步OC奖励相同，两个都计入月度总奖励")
    void testCalculateIncome_TwoSingleOcSameReward_bothCounted() {
        TornFactionOcDO oc1 = createOc(null, TornConstants.OC_NAME_BREAK_THE_BANK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 1000000L, null);
        TornFactionOcDO oc2 = createOc(null, TornConstants.OC_NAME_BREAK_THE_BANK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 12, 10, 0), 1000000L, null);
        createSlot(oc1.getId(), USER_ID_1, "Thief#1", 60, 50000L);
        createSlot(oc2.getId(), USER_ID_1, "Thief#1", 60, 50000L);

        incomeService.calculateAndSaveIncome(oc1);
        incomeService.calculateAndSaveIncome(oc2);

        TornFactionOcIncomeSummaryDO summary = querySummary(USER_ID_1, "2026-04");
        assertEquals(2000000L, summary.getTotalReward());
    }

    @Test
    @DisplayName("同月两条链奖励相同，两条都计入月度总奖励")
    void testCalculateIncome_TwoChainsSameReward_bothCounted() {
        TornFactionOcDO chain1Step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 5, 10, 0), 0L, null);
        TornFactionOcDO chain1Step2 = createOc(chain1Step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 6, 10, 0), 1000000L, null);
        TornFactionOcDO chain2Step1 = createOc(null, TornConstants.OC_NAME_LOCK_STOCK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 7, 10, 0), 0L, null);
        TornFactionOcDO chain2Step2 = createOc(chain2Step1.getId(), TornConstants.OC_NAME_HOSTILE_TAKEOVER, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 8, 10, 0), 1000000L, null);
        createSlot(chain1Step1.getId(), USER_ID_1, "Imitator#1", 80, 50000L);
        createSlot(chain1Step2.getId(), USER_ID_1, "Imitator#1", 75, 50000L);
        createSlot(chain2Step1.getId(), USER_ID_1, "Hacker#1", 80, 50000L);
        createSlot(chain2Step2.getId(), USER_ID_1, "Hacker#1", 75, 50000L);

        incomeService.calculateAndSaveIncome(chain1Step2);
        incomeService.calculateAndSaveIncome(chain2Step2);

        TornFactionOcIncomeSummaryDO summary = querySummary(USER_ID_1, "2026-04");
        assertEquals(2000000L, summary.getTotalReward());
    }

    @Test
    @DisplayName("一条链多个节点、多个成员，只计一次奖励")
    void testCalculateIncome_ChainMultiNodeMultiMember_rewardCountedOnce() {
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 0L, null);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 20, 15, 0), 1000000L, null);
        createSlot(step1.getId(), USER_ID_1, "Imitator#1", 80, 50000L);
        createSlot(step1.getId(), USER_ID_2, "Imitator#2", 70, 30000L);
        createSlot(step2.getId(), USER_ID_1, "Imitator#1", 75, 40000L);
        createSlot(step2.getId(), USER_ID_2, "Imitator#2", 65, 20000L);

        incomeService.calculateAndSaveIncome(step2);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .in(TornFactionOcIncomeDO::getOcId, List.of(step1.getId(), step2.getId()))
                .list();
        assertEquals(4, incomes.size());
        TornFactionOcIncomeSummaryDO summary = querySummary(USER_ID_1, "2026-04");
        assertEquals(1000000L, summary.getTotalReward());
    }

    @Test
    @DisplayName("单步OC和链奖励相同，分别计入")
    void testCalculateIncome_SingleOcAndChainSameReward_bothCounted() {
        TornFactionOcDO single = createOc(null, TornConstants.OC_NAME_BREAK_THE_BANK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 1000000L, null);
        TornFactionOcDO chainStep1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 12, 10, 0), 0L, null);
        TornFactionOcDO chainStep2 = createOc(chainStep1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 13, 10, 0), 1000000L, null);
        createSlot(single.getId(), USER_ID_1, "Thief#1", 60, 50000L);
        createSlot(chainStep1.getId(), USER_ID_1, "Imitator#1", 80, 50000L);
        createSlot(chainStep2.getId(), USER_ID_1, "Imitator#1", 75, 50000L);

        incomeService.calculateAndSaveIncome(single);
        incomeService.calculateAndSaveIncome(chainStep2);

        TornFactionOcIncomeSummaryDO summary = querySummary(USER_ID_1, "2026-04");
        assertEquals(2000000L, summary.getTotalReward());
    }

    @Test
    @DisplayName("失败OC奖励为0，道具损失计入")
    void testCalculateIncome_FailedOc_rewardZeroItemCostCounted() {
        TornFactionOcDO oc = createOc(null, TornConstants.OC_NAME_BREAK_THE_BANK, 8, TornOcStatusEnum.FAILURE,
                LocalDateTime.of(2026, 4, 15, 10, 0), 0L, null);
        createSlot(oc.getId(), USER_ID_1, "Thief#1", 60, 50000L);
        createSlot(oc.getId(), USER_ID_2, "Thief#2", 70, 30000L);

        incomeService.calculateAndSaveIncome(oc);

        TornFactionOcIncomeSummaryDO summary = querySummary(USER_ID_1, "2026-04");
        assertEquals(0L, summary.getTotalReward());
        assertEquals(50000L, summary.getTotalItemCost());
    }

    @Test
    @DisplayName("同一结算叶子出现不同totalReward时fail-closed")
    void testCalculateIncome_SameLeafDifferentReward_failClosed() {
        TornFactionOcDO oc = createOc(null, TornConstants.OC_NAME_BREAK_THE_BANK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 1000000L, null);
        createSlot(oc.getId(), USER_ID_1, "Thief#1", 60, 50000L);

        // 手工构造同一叶子两条不同totalReward的income记录，模拟数据异常
        insertIncome(oc, USER_ID_1, "Thief#1", 1000000L);
        insertIncome(oc, USER_ID_2, "Thief#2", 2000000L);

        assertThrows(BizException.class,
                () -> incomeService.calcMonthlyIncomeSummary(FACTION_ID, "2026-04"));
    }

    private TornFactionOcIncomeSummaryDO querySummary(Long userId, String yearMonth) {
        return incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getUserId, userId)
                .eq(TornFactionOcIncomeSummaryDO::getFactionId, FACTION_ID)
                .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                .one();
    }

    private void insertIncome(TornFactionOcDO oc, Long userId, String position, Long totalReward) {
        TornFactionOcIncomeDO income = new TornFactionOcIncomeDO();
        income.setOcId(oc.getId());
        income.setFactionId(oc.getFactionId());
        income.setOcName(oc.getName());
        income.setRank(oc.getRank());
        income.setOcExecutedTime(oc.getExecutedTime());
        income.setUserId(userId);
        income.setPosition(position);
        income.setPassRate(60);
        income.setBaseWorkingHours(2);
        income.setCoefficient(BigDecimal.valueOf(15));
        income.setEffectiveWorkingHours(BigDecimal.valueOf(30));
        income.setIsSuccess(true);
        income.setTotalReward(totalReward);
        income.setItemCost(0L);
        income.setTotalItemCost(0L);
        income.setFinalIncome(totalReward);
        incomeDao.save(income);
    }

    private TornFactionOcDO createOc(Long previousOcId, String name, Integer rank,
                                     TornOcStatusEnum status, LocalDateTime executedTime,
                                     Long rewardMoney, String rewardItems) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setFactionId(FACTION_ID);
        oc.setPreviousOcId(previousOcId);
        oc.setName(name);
        oc.setRank(rank);
        oc.setStatus(status.getCode());
        oc.setExecutedTime(executedTime);
        oc.setRewardMoney(rewardMoney);
        oc.setRewardItemsValue(rewardItems);
        ocDao.save(oc);
        return oc;
    }

    private void createSlot(Long ocId, Long userId, String position, Integer passRate, Long itemValue) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        slot.setPassRate(passRate);
        slot.setOutcomeItemValue(itemValue);
        ocSlotDao.save(slot);
    }
}
