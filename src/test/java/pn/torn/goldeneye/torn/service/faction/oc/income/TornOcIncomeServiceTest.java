package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        TornFactionOcDO oc = createOc(null, "Break the Bank", 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 1000000L);
        createSlot(oc.getId(), USER_ID_1, "Thief#1", 60, 50000L);
        createSlot(oc.getId(), USER_ID_2, "Thief#2", 70, 30000L);

        incomeService.calculateAndSaveIncome(oc);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, oc.getId())
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
        TornFactionOcDO step1 = createOc(null, "Stacking the Deck", 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), "Ace in the Hole", 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 20, 15, 0), 2000000L);

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
        assertEquals("Stacking the Deck", income1.getOcName());
        assertEquals(8, income1.getRank());
        assertEquals(USER_ID_1, income1.getUserId());

        TornFactionOcIncomeDO income2 = incomes.stream()
                .filter(i -> i.getOcId().equals(step2.getId()))
                .findFirst().orElseThrow();
        assertEquals("Ace in the Hole", income2.getOcName());
        assertEquals(9, income2.getRank());
        assertEquals(USER_ID_2, income2.getUserId());

        assertTrue(incomes.stream().allMatch(i -> i.getTotalReward() == 2000000L));
        assertTrue(incomes.stream().allMatch(i -> i.getTotalItemCost() == 250000L));
    }

    @Test
    @DisplayName("Chain OC跨月计算")
    void testCalculateIncome_ChainOc_CrossMonth() {
        // 链式OC，跨月完成
        TornFactionOcDO step1 = createOc(null, "Stacking the Deck", 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 3, 28, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), "Ace in the Hole", 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 2, 15, 0), 1500000L);

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
        TornFactionOcDO step1 = createOc(null, "Stacking the Deck", 8, TornOcStatusEnum.FAILURE,
                LocalDateTime.of(2026, 4, 15, 10, 0), 0L);

        createSlot(step1.getId(), USER_ID_1, "Hacker#1", 60, 50000L);

        incomeService.calculateAndSaveIncome(step1);

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, step1.getId())
                .list();

        assertEquals(1, incomes.size());
        assertFalse(incomes.getFirst().getIsSuccess());
        assertEquals(0L, incomes.getFirst().getTotalReward());
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