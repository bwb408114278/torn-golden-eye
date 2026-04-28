package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大锅饭OC采样范围获取集成测试
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.04.20
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("大锅饭收入入口测试")
class TornOcBatchIncomeServiceTest {
    @Autowired
    private TornOcBatchIncomeService batchIncomeService;
    @Autowired
    private TornFactionOcDAO ocDao;
    @Autowired
    private TornFactionOcSlotDAO ocSlotDao;
    @Autowired
    private TornFactionOcIncomeDAO incomeDao;

    private static final Long FACTION_ID = 1000L;
    private static final Long USER_ID = 2001L;

    @BeforeEach
    void setUp() {
        // 模拟 ROTATION_OC_NAME 配置
        TornConstants.ROTATION_OC_NAME.put(FACTION_ID, List.of("Stacking the Deck", "Ace in the Hole"));
    }

    @Test
    @DisplayName("只处理结算的OC")
    void testBatchCalculateIncome_OnlyLeafNodes() {
        // 创建链式OC：step1 -> step2，只有 step2 应该被处理
        TornFactionOcDO step1 = createOc(null, "Stacking the Deck", 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), "Ace in the Hole", 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 15, 0), 1000000L);

        createSlot(step1.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(step2.getId(), USER_ID, "Imitator#1", 70, 30000L);

        batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        // step1 不应该有独立的 income 记录（它的数据应该合并到 step2 的计算中）
        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .list();

        assertEquals(2, incomes.size());
        assertTrue(incomes.stream().anyMatch(i -> i.getOcId().equals(step1.getId())));
        assertTrue(incomes.stream().anyMatch(i -> i.getOcId().equals(step2.getId())));
    }

    @Test
    @DisplayName("跳过已结算的OC")
    void testBatchCalculateIncome_SkipAlreadyCalculated() {
        // 已有 income 记录的 OC 不应该重复计算
        TornFactionOcDO oc = createOc(null, "Ace in the Hole", 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc.getId(), USER_ID, "Driver#1", 60, 20000L);

        // 手动创建 income 记录
        TornFactionOcIncomeDO existingIncome = new TornFactionOcIncomeDO();
        existingIncome.setFactionId(FACTION_ID);
        existingIncome.setOcId(oc.getId());
        existingIncome.setOcName(oc.getName());
        existingIncome.setRank(oc.getRank());
        existingIncome.setOcExecutedTime(oc.getExecutedTime());
        existingIncome.setUserId(USER_ID);
        existingIncome.setPosition("Driver#1");
        existingIncome.setPassRate(60);
        existingIncome.setBaseWorkingHours(4);
        existingIncome.setCoefficient(BigDecimal.valueOf(15));
        existingIncome.setEffectiveWorkingHours(BigDecimal.valueOf(60));
        existingIncome.setIsSuccess(true);
        incomeDao.save(existingIncome);

        batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        // 应该只有之前手动创建的那一条
        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, oc.getId())
                .list();

        assertEquals(1, incomes.size());
    }

    @Test
    @DisplayName("非大锅饭OC不处理")
    void testBatchCalculateIncome_OnlyRotationOcs() {
        // 非轮换 OC 不应该被处理
        TornFactionOcDO rotationOc = createOc(null, "Ace in the Hole", 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        TornFactionOcDO nonRotationOc = createOc(null, "Manifest Cruelty", 10, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 11, 0), 300000L);

        createSlot(rotationOc.getId(), USER_ID, "Muscle#1", 65, 20000L);
        createSlot(nonRotationOc.getId(), USER_ID, "Reviver#1", 70, 10000L);

        batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .list();

        assertEquals(1, incomes.size());
        assertEquals(rotationOc.getId(), incomes.getFirst().getOcId());
    }

    @Test
    @DisplayName("多个OC同时处理")
    void testBatchCalculateIncome_MultipleCompleteOcs() {
        // 多个已完成的叶子节点 OC
        TornFactionOcDO oc1 = createOc(null, "Ace in the Hole", 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 800000L);
        TornFactionOcDO oc2 = createOc(null, "Ace in the Hole", 9, TornOcStatusEnum.FAILURE,
                LocalDateTime.of(2026, 4, 12, 10, 0), 0L);

        createSlot(oc1.getId(), USER_ID, "Imitator#1", 70, 40000L);
        createSlot(oc2.getId(), USER_ID, "Imitator#1", 65, 30000L);

        batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .orderByAsc(TornFactionOcIncomeDO::getOcExecutedTime)
                .list();

        assertEquals(2, incomes.size());
        assertTrue(incomes.get(0).getIsSuccess());
        assertFalse(incomes.get(1).getIsSuccess());
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