package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.BatchIncomeResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大锅饭OC批量收益入口集成测试。
 *
 * <p>批量门面本身不持有事务，每个叶子在独立事务Worker中提交，因此本测试不使用事务回滚，
 * 改为在@AfterEach通过持久层按测试创建数据定向清理（逻辑删除），避免污染开发库。
 * 同时验证链配置按名称加等级匹配、等待后继与异常部分income统计。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.04.20
 */
@SpringBootTest
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
    @Autowired
    private TornFactionOcIncomeSummaryDAO incomeSummaryDao;
    @Autowired
    private TornSettingOcChainDAO ocChainDao;

    private static final Long FACTION_ID = 1000L;
    private static final Long USER_ID = 2001L;
    private static final List<Long> TEST_FACTIONS = List.of(FACTION_ID, TornConstants.FACTION_PN_ID,
            TornConstants.FACTION_NOV_ID, TornConstants.FACTION_HP_ID);
    private static final List<String> TEST_MONTHS = List.of("2026-04", "2026-06", "2026-07", "2026-08");
    private static final List<String> ROTATION_NAMES = List.of(
            TornConstants.OC_NAME_ACE_IN_THE_HOLE, TornConstants.OC_NAME_STACKING_THE_DECK,
            TornConstants.OC_NAME_LOCK_STOCK, TornConstants.OC_NAME_HOSTILE_TAKEOVER,
            TornConstants.OC_NAME_MANIFEST_CRUELTY, TornConstants.OC_NAME_GONE_FISSION,
            TornConstants.OC_NAME_CRANE_REACTION);

    private final List<Long> createdOcIds = new ArrayList<>();
    private final List<String> testChainCodes = new ArrayList<>();
    private List<String> originalRotationList;

    @BeforeEach
    void setUp() {
        originalRotationList = TornConstants.ROTATION_OC_NAME.get(FACTION_ID);
        TornConstants.ROTATION_OC_NAME.put(FACTION_ID, ROTATION_NAMES);
        // Lock Stock -> Hostile Takeover 链配置在生产由管理员手工维护，测试使用唯一链编码自插保证确定性
        insertChainConfig(TornConstants.OC_NAME_LOCK_STOCK, 8,
                TornConstants.OC_NAME_HOSTILE_TAKEOVER, 9, true);
    }

    @AfterEach
    void cleanup() {
        // 通过持久层清理测试数据（逻辑删除），不直接操作SQL，保持与生产删除语义一致
        if (!createdOcIds.isEmpty()) {
            incomeDao.lambdaUpdate().in(TornFactionOcIncomeDO::getOcId, createdOcIds).remove();
            ocSlotDao.lambdaUpdate().in(TornFactionOcSlotDO::getOcId, createdOcIds).remove();
            ocDao.lambdaUpdate().in(TornFactionOcDO::getId, createdOcIds).remove();
        }
        incomeSummaryDaoCleanup();
        if (!testChainCodes.isEmpty()) {
            ocChainDao.lambdaUpdate().in(TornSettingOcChainDO::getChainCode, testChainCodes).remove();
        }
        if (originalRotationList == null) {
            TornConstants.ROTATION_OC_NAME.remove(FACTION_ID);
        } else {
            TornConstants.ROTATION_OC_NAME.put(FACTION_ID, originalRotationList);
        }
        for (Long factionId : TEST_FACTIONS) {
            batchIncomeService.releaseFactionCalculateLock(factionId);
        }
    }

    @Test
    @DisplayName("只处理结算的OC")
    void testBatchCalculateIncome_OnlyLeafNodes() {
        // 创建链式OC：step1 -> step2，只有 step2 应该被处理
        TornFactionOcDO step1 = createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 0L);
        TornFactionOcDO step2 = createOc(step1.getId(), TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 15, 0), 1000000L);

        createSlot(step1.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(step2.getId(), USER_ID, "Imitator#1", 70, 30000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertNotNull(result);
        assertEquals(1, result.candidateCount());
        assertEquals(1, result.successCount());
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
        TornFactionOcDO oc = createOc(null, TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc.getId(), USER_ID, "Driver#1", 60, 20000L);

        // 手动创建 income 记录
        insertIncome(oc, USER_ID, "Driver#1", true, 500000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(0, result.candidateCount());
        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getOcId, oc.getId())
                .list();
        assertEquals(1, incomes.size());
    }

    @Test
    @DisplayName("非大锅饭OC不处理")
    void testBatchCalculateIncome_OnlyRotationOcs() {
        // 非轮换 OC 不应该被处理
        TornFactionOcDO rotationOc = createOc(null, TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        TornFactionOcDO nonRotationOc = createOc(null, TornConstants.OC_NAME_WINDOW_OF_OPPORTUNITY, 10,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 15, 11, 0), 300000L);

        createSlot(rotationOc.getId(), USER_ID, "Muscle#1", 70, 20000L);
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
        TornFactionOcDO oc1 = createOc(null, TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 10, 10, 0), 800000L);
        TornFactionOcDO oc2 = createOc(null, TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.FAILURE,
                LocalDateTime.of(2026, 4, 12, 10, 0), 0L);

        createSlot(oc1.getId(), USER_ID, "Imitator#1", 70, 40000L);
        createSlot(oc2.getId(), USER_ID, "Imitator#1", 70, 30000L);

        batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .orderByAsc(TornFactionOcIncomeDO::getOcExecutedTime)
                .list();
        assertEquals(2, incomes.size());
        assertTrue(incomes.get(0).getIsSuccess());
        assertFalse(incomes.get(1).getIsSuccess());
    }

    @Test
    @DisplayName("PN大锅饭收益从2026-08-01起扫描")
    void testBatchCalculateIncome_PnStartTimeBoundary() {
        // 生效前完成的新增OC不生成大锅饭收益
        TornFactionOcDO before = createOc(null, TornConstants.OC_NAME_HOSTILE_TAKEOVER, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 7, 31, 23, 59, 59), 1000000L, TornConstants.FACTION_PN_ID);
        // 生效时刻完成的新增OC生成大锅饭收益
        TornFactionOcDO onStart = createOc(null, TornConstants.OC_NAME_HOSTILE_TAKEOVER, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 8, 1, 0, 0, 0), 1000000L, TornConstants.FACTION_PN_ID);
        createSlot(before.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(onStart.getId(), USER_ID, "Hacker#1", 65, 50000L);

        batchIncomeService.batchCalculateIncome(TornConstants.FACTION_PN_ID, LocalDateTime.of(2026, 8, 10, 0, 0, 0));

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, TornConstants.FACTION_PN_ID)
                .in(TornFactionOcIncomeDO::getOcId, List.of(before.getId(), onStart.getId()))
                .list();
        assertEquals(1, incomes.size());
        assertEquals(onStart.getId(), incomes.getFirst().getOcId());
    }

    @Test
    @DisplayName("NOV大锅饭收益从2026-07-01起扫描")
    void testBatchCalculateIncome_NovStartTimeBoundary() {
        // 生效前完成的新增OC不生成大锅饭收益
        TornFactionOcDO before = createOc(null, TornConstants.OC_NAME_CRANE_REACTION, 10, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 6, 30, 23, 59, 59), 1000000L, TornConstants.FACTION_NOV_ID);
        // 生效时刻完成的新增OC生成大锅饭收益
        TornFactionOcDO onStart = createOc(null, TornConstants.OC_NAME_CRANE_REACTION, 10, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 7, 1, 0, 0, 0), 1000000L, TornConstants.FACTION_NOV_ID);
        createSlot(before.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(onStart.getId(), USER_ID, "Hacker#1", 65, 50000L);

        batchIncomeService.batchCalculateIncome(TornConstants.FACTION_NOV_ID, LocalDateTime.of(2026, 7, 10, 0, 0, 0));

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, TornConstants.FACTION_NOV_ID)
                .in(TornFactionOcIncomeDO::getOcId, List.of(before.getId(), onStart.getId()))
                .list();
        assertEquals(1, incomes.size());
        assertEquals(onStart.getId(), incomes.getFirst().getOcId());
    }

    @Test
    @DisplayName("其他帮派保持当前月扫描，上月完成不生成")
    void testBatchCalculateIncome_OtherFactionCurrentMonthFilter() {
        TornFactionOcDO oc = createOc(null, TornConstants.OC_NAME_BREAK_THE_BANK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 6, 15, 10, 0), 800000L, TornConstants.FACTION_HP_ID);
        createSlot(oc.getId(), USER_ID, "Muscle#1", 70, 20000L);

        // 当前月份为2026-07，上月(2026-06)完成的OC不生成
        batchIncomeService.batchCalculateIncome(TornConstants.FACTION_HP_ID, LocalDateTime.of(2026, 7, 10, 0, 0, 0));

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, TornConstants.FACTION_HP_ID)
                .eq(TornFactionOcIncomeDO::getOcId, oc.getId())
                .list();
        assertEquals(0, incomes.size());
    }

    @Test
    @DisplayName("成功链父节点在后继未同步前不提前计算")
    void testBatchCalculateIncome_WaitingChainParentSkipped() {
        // Lock Stock 是配置链父节点，无后继节点时应等待，不生成收益
        TornFactionOcDO lockStock = createOc(null, TornConstants.OC_NAME_LOCK_STOCK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        createSlot(lockStock.getId(), USER_ID, "Hacker#1", 65, 50000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(1, result.waitingChainParentCount());
        assertEquals(0, result.successCount());
        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .eq(TornFactionOcIncomeDO::getOcId, lockStock.getId())
                .list();
        assertEquals(0, incomes.size());
    }

    @Test
    @DisplayName("链式OC仅由叶子节点触发整条链收益")
    void testBatchCalculateIncome_ChainLeafTriggersWholeChain() {
        // Lock Stock -> Hostile Takeover，仅叶子节点 Hostile Takeover 触发整条链收益
        TornFactionOcDO lockStock = createOc(null, TornConstants.OC_NAME_LOCK_STOCK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO hostile = createOc(lockStock.getId(), TornConstants.OC_NAME_HOSTILE_TAKEOVER, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 1000000L);
        createSlot(lockStock.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(hostile.getId(), USER_ID, "Imitator#1", 70, 30000L);

        batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .in(TornFactionOcIncomeDO::getOcId, List.of(lockStock.getId(), hostile.getId()))
                .list();
        assertEquals(2, incomes.size());
    }

    @Test
    @DisplayName("根节点已结算但叶子仍待计算时识别为异常部分income，不新增明细")
    void testBatchCalculateIncome_SkipWhenAncestorAlreadySettled() {
        // 根节点 Lock Stock 已被误结算，叶子 Hostile Takeover 尚未结算
        TornFactionOcDO lockStock = createOc(null, TornConstants.OC_NAME_LOCK_STOCK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        TornFactionOcDO hostile = createOc(lockStock.getId(), TornConstants.OC_NAME_HOSTILE_TAKEOVER, 9,
                TornOcStatusEnum.SUCCESSFUL, LocalDateTime.of(2026, 4, 2, 10, 0), 1000000L);
        createSlot(lockStock.getId(), USER_ID, "Hacker#1", 65, 50000L);
        createSlot(hostile.getId(), USER_ID, "Imitator#1", 70, 30000L);

        // 手动为根节点生成income，模拟早期误结算
        insertIncome(lockStock, USER_ID, "Hacker#1", true, 1000000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        // 根节点income不应重复，叶子也不应生成，整条链被识别为异常部分income
        assertEquals(1, result.abnormalPartialIncomeCount());
        assertEquals(0, result.successCount());
        assertEquals(1, result.abnormalChains().size());
        assertEquals(hostile.getId(), result.abnormalChains().getFirst().leafOcId());
        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .in(TornFactionOcIncomeDO::getOcId, List.of(lockStock.getId(), hostile.getId()))
                .list();
        assertEquals(1, incomes.size());
        assertEquals(lockStock.getId(), incomes.getFirst().getOcId());
    }

    @Test
    @DisplayName("同名不同等级的OC不作为等待父节点")
    void testChainConfig_sameNameDifferentRank_notWait() {
        // 配置父节点是 Lock Stock(8)，rank=9 的同名OC是独立终点，应立即处理而不是等待
        TornFactionOcDO lockStock9 = createOc(null, TornConstants.OC_NAME_LOCK_STOCK, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 10, 0), 1000000L);
        createSlot(lockStock9.getId(), USER_ID, "Hacker#1", 65, 50000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(1, result.successCount());
        assertEquals(0, result.waitingChainParentCount());
    }

    @Test
    @DisplayName("enabled=false的链配置不作为等待父节点")
    void testChainConfig_disabledConfig_notWait() {
        insertChainConfig(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 5,
                TornConstants.OC_NAME_STACKING_THE_DECK, 8, false);
        TornFactionOcDO ace5 = createOc(null, TornConstants.OC_NAME_ACE_IN_THE_HOLE, 5, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 10, 0), 1000000L);
        createSlot(ace5.getId(), USER_ID, "Driver#1", 60, 20000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(1, result.successCount());
        assertEquals(0, result.waitingChainParentCount());
    }

    @Test
    @DisplayName("逻辑删除的链配置不作为等待父节点")
    void testChainConfig_logicDeletedConfig_notWait() {
        TornSettingOcChainDO chain = new TornSettingOcChainDO();
        chain.setChainCode("TEST_CHAIN_" + System.nanoTime());
        chain.setParentOcName(TornConstants.OC_NAME_ACE_IN_THE_HOLE);
        chain.setParentRank(6);
        chain.setChildOcName(TornConstants.OC_NAME_STACKING_THE_DECK);
        chain.setChildRank(8);
        chain.setSequenceNo(1);
        chain.setEnabled(true);
        chain.setDeleted(1);
        ocChainDao.save(chain);
        testChainCodes.add(chain.getChainCode());

        TornFactionOcDO ace6 = createOc(null, TornConstants.OC_NAME_ACE_IN_THE_HOLE, 6, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 10, 0), 1000000L);
        createSlot(ace6.getId(), USER_ID, "Driver#1", 60, 20000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(1, result.successCount());
        assertEquals(0, result.waitingChainParentCount());
    }

    @Test
    @DisplayName("失败配置链父节点立即按终点结算损失")
    void testChainConfig_failedParent_settlesLoss() {
        // Lock Stock(8) 失败，不应等待后继，直接作为终点计算损失
        TornFactionOcDO lockStock = createOc(null, TornConstants.OC_NAME_LOCK_STOCK, 8, TornOcStatusEnum.FAILURE,
                LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        createSlot(lockStock.getId(), USER_ID, "Hacker#1", 65, 50000L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(1, result.successCount());
        assertEquals(0, result.waitingChainParentCount());
        List<TornFactionOcIncomeDO> incomes = incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID)
                .eq(TornFactionOcIncomeDO::getOcId, lockStock.getId())
                .list();
        assertEquals(1, incomes.size());
        assertFalse(incomes.getFirst().getIsSuccess());
    }

    @Test
    @DisplayName("三条生产链父节点在后继未同步前均等待")
    void testChainConfig_threeProductionChains_parentsWait() {
        // Stacking the Deck -> Ace in the Hole
        createOc(null, TornConstants.OC_NAME_STACKING_THE_DECK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 10, 0), 0L);
        // Lock Stock -> Hostile Takeover
        createOc(null, TornConstants.OC_NAME_LOCK_STOCK, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 11, 0), 0L);
        // Manifest Cruelty -> Gone Fission -> Crane Reaction
        createOc(null, TornConstants.OC_NAME_MANIFEST_CRUELTY, 8, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 1, 12, 0), 0L);

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(3, result.waitingChainParentCount());
        assertEquals(0, result.successCount());
        assertEquals(0L, incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, FACTION_ID).count());
    }

    /**
     * 插入一条测试链配置，使用唯一链编码便于逻辑删除清理而不与唯一约束冲突。
     *
     * @param parentName 前置OC名称
     * @param parentRank 前置OC等级
     * @param childName  后继OC名称
     * @param childRank  后继OC等级
     * @param enabled    是否启用
     */
    private void insertChainConfig(String parentName, Integer parentRank,
                                   String childName, Integer childRank, boolean enabled) {
        TornSettingOcChainDO chain = new TornSettingOcChainDO();
        chain.setChainCode("TEST_CHAIN_" + System.nanoTime());
        chain.setParentOcName(parentName);
        chain.setParentRank(parentRank);
        chain.setChildOcName(childName);
        chain.setChildRank(childRank);
        chain.setSequenceNo(1);
        chain.setEnabled(enabled);
        ocChainDao.save(chain);
        testChainCodes.add(chain.getChainCode());
    }

    /**
     * 通过持久层逻辑删除本测试帮派与月份下测试用户的汇总数据。
     */
    private void incomeSummaryDaoCleanup() {
        incomeSummaryDao.lambdaUpdate()
                .eq(TornFactionOcIncomeSummaryDO::getUserId, USER_ID)
                .in(TornFactionOcIncomeSummaryDO::getFactionId, TEST_FACTIONS)
                .in(TornFactionOcIncomeSummaryDO::getYearMonth, TEST_MONTHS)
                .remove();
    }

    private void insertIncome(TornFactionOcDO oc, Long userId, String position, boolean isSuccess, Long totalReward) {
        TornFactionOcIncomeDO income = new TornFactionOcIncomeDO();
        income.setFactionId(oc.getFactionId());
        income.setOcId(oc.getId());
        income.setOcName(oc.getName());
        income.setRank(oc.getRank());
        income.setOcExecutedTime(oc.getExecutedTime());
        income.setUserId(userId);
        income.setPosition(position);
        income.setPassRate(60);
        income.setBaseWorkingHours(4);
        income.setCoefficient(BigDecimal.valueOf(15));
        income.setEffectiveWorkingHours(BigDecimal.valueOf(60));
        income.setIsSuccess(isSuccess);
        income.setTotalReward(totalReward);
        income.setItemCost(0L);
        income.setTotalItemCost(0L);
        income.setFinalIncome(totalReward);
        incomeDao.save(income);
    }

    private TornFactionOcDO createOc(Long previousOcId, String name, Integer rank,
                                     TornOcStatusEnum status, LocalDateTime executedTime, Long rewardMoney) {
        return createOc(previousOcId, name, rank, status, executedTime, rewardMoney, FACTION_ID);
    }

    private TornFactionOcDO createOc(Long previousOcId, String name, Integer rank,
                                     TornOcStatusEnum status, LocalDateTime executedTime, Long rewardMoney,
                                     Long factionId) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setFactionId(factionId);
        oc.setPreviousOcId(previousOcId);
        oc.setName(name);
        oc.setRank(rank);
        oc.setStatus(status.getCode());
        oc.setExecutedTime(executedTime);
        oc.setRewardMoney(rewardMoney);
        ocDao.save(oc);
        createdOcIds.add(oc.getId());
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
