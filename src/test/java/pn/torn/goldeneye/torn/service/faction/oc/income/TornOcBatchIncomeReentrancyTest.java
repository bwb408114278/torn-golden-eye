package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/**
 * 大锅饭批量计算按帮派防重入测试。
 *
 * <p>同一帮派同一时刻只允许一个批量收益计算流程，抢占失败直接返回；不同帮派可以并行；
 * 防重入标记在异常路径也必须在finally释放，且释放发生在Worker事务返回之后。本测试通过
 * 真实调用批量入口验证锁与事务行为，不开启事务，方便验证跨线程的提交与清理。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
@SpringBootTest
@DisplayName("大锅饭批量计算防重入测试")
class TornOcBatchIncomeReentrancyTest {
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
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private TornOcIncomeTransactionWorker transactionWorker;

    private static final Long FACTION_ID = 999002L;
    private static final Long USER_ID = 888002L;

    private final List<Long> createdOcIds = new ArrayList<>();
    private List<String> originalRotationList;

    @BeforeEach
    void setUp() {
        originalRotationList = TornConstants.ROTATION_OC_NAME.get(FACTION_ID);
        TornConstants.ROTATION_OC_NAME.put(FACTION_ID, List.of(TornConstants.OC_NAME_ACE_IN_THE_HOLE));
    }

    @AfterEach
    void cleanup() {
        // 通过JdbcTemplate物理删除测试数据，确保数据库干净，避免逻辑删除残留deleted=1记录
        if (!createdOcIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM torn_faction_oc_income WHERE oc_id IN (" +
                    createdOcIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + ")");
            jdbcTemplate.update("DELETE FROM torn_faction_oc_slot WHERE oc_id IN (" +
                    createdOcIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + ")");
            jdbcTemplate.update("DELETE FROM torn_faction_oc WHERE id IN (" +
                    createdOcIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + ")");
        }
        // 清理本测试生成的汇总与历史遗留测试汇总（faction_id=999002, year_month=2026-04, user_id=888002）
        jdbcTemplate.update("DELETE FROM torn_faction_oc_income_summary WHERE user_id = ?", USER_ID);
        if (originalRotationList == null) {
            TornConstants.ROTATION_OC_NAME.remove(FACTION_ID);
        } else {
            TornConstants.ROTATION_OC_NAME.put(FACTION_ID, originalRotationList);
        }
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID);
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID + 1L);
        Mockito.reset(transactionWorker);
    }

    @Test
    @DisplayName("同帮派防重入标记互斥且可释放")
    void factionLock_isMutuallyExclusiveAndReleasable() {
        assertTrue(batchIncomeService.tryAcquireFactionCalculateLock(FACTION_ID));
        assertFalse(batchIncomeService.tryAcquireFactionCalculateLock(FACTION_ID));

        batchIncomeService.releaseFactionCalculateLock(FACTION_ID);
        assertTrue(batchIncomeService.tryAcquireFactionCalculateLock(FACTION_ID));

        // 不同帮派可以并行持有
        assertTrue(batchIncomeService.tryAcquireFactionCalculateLock(FACTION_ID + 1L));
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID + 1L);
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID);
    }

    @Test
    @DisplayName("持锁期间调用直接返回，不执行计算")
    void batchCalculateIncome_returnsEarlyWhenFactionLockHeld() {
        TornFactionOcDO oc = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc.getId(), USER_ID, "Driver#1", 70, 20000L);

        assertTrue(batchIncomeService.tryAcquireFactionCalculateLock(FACTION_ID));
        try {
            // 持锁期间调用应直接返回，不生成收益
            assertNull(batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
            assertEquals(0, countIncome(oc.getId()));
        } finally {
            batchIncomeService.releaseFactionCalculateLock(FACTION_ID);
        }

        // 释放后可正常执行
        batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));
        assertEquals(1, countIncome(oc.getId()));
    }

    @Test
    @DisplayName("同帮派并发调用不产生重复收益")
    void concurrentSameFactionCalls_doNotDuplicate() throws Exception {
        TornFactionOcDO oc = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc.getId(), USER_ID, "Driver#1", 70, 20000L);

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 无论并发时序如何，同一OC只生成一条收益和一套汇总
        assertEquals(1, countIncome(oc.getId()));
        assertEquals(1L, countSummary(USER_ID));
    }

    @Test
    @DisplayName("同帮派并发：第一线程阻塞在Worker内时第二线程直接返回，释放后只产生一套收益与汇总")
    void sameFaction_secondCallReturnsEarlyWhileFirstInWorker() throws Exception {
        TornFactionOcDO oc = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc.getId(), USER_ID, "Driver#1", 70, 20000L);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(transactionWorker).processSingleChain(anyLong(), anyLong(), any(LocalDateTime.class), anySet(), anyList());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<BatchIncomeResult> first = pool.submit(() ->
                batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        // 第一线程持锁并阻塞在Worker事务内，第二线程应直接返回且未执行计算
        assertNull(batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
        assertEquals(0, countIncome(oc.getId()));

        release.countDown();
        BatchIncomeResult firstResult = first.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertNotNull(firstResult);
        assertEquals(1, firstResult.successCount());
        assertEquals(1, countIncome(oc.getId()));
        assertEquals(1L, countSummary(USER_ID));
    }

    @Test
    @DisplayName("不同帮派并发：两个线程均能进入各自的Worker，不存在全局串行锁")
    void differentFactions_runInParallel() throws Exception {
        Long factionB = FACTION_ID + 1L;
        TornConstants.ROTATION_OC_NAME.put(factionB, List.of(TornConstants.OC_NAME_ACE_IN_THE_HOLE));
        try {
            TornFactionOcDO ocA = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                    LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
            createSlot(ocA.getId(), USER_ID, "Driver#1", 70, 20000L);
            TornFactionOcDO ocB = createOcForFaction(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                    LocalDateTime.of(2026, 4, 15, 10, 0), 500000L, factionB);
            createSlotForFaction(ocB.getId(), USER_ID, "Driver#1", 70, 20000L);

            CyclicBarrier barrier = new CyclicBarrier(2);
            doAnswer(invocation -> {
                barrier.await(10, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(transactionWorker).processSingleChain(anyLong(), anyLong(), any(LocalDateTime.class), anySet(), anyList());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<BatchIncomeResult> fA = pool.submit(() ->
                    batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
            Future<BatchIncomeResult> fB = pool.submit(() ->
                    batchIncomeService.batchCalculateIncome(factionB, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
            BatchIncomeResult rA = fA.get(10, TimeUnit.SECONDS);
            BatchIncomeResult rB = fB.get(10, TimeUnit.SECONDS);
            pool.shutdown();

            // 两个线程均进入了各自的Worker并各自成功，证明没有全局串行锁
            assertNotNull(rA);
            assertNotNull(rB);
            assertEquals(1, rA.successCount());
            assertEquals(1, rB.successCount());
            assertEquals(1L, countIncomeForFaction(ocA.getId(), FACTION_ID));
            assertEquals(1L, countIncomeForFaction(ocB.getId(), factionB));
        } finally {
            TornConstants.ROTATION_OC_NAME.remove(factionB);
        }
    }

    @Test
    @DisplayName("Worker抛异常后锁在finally释放，再次调用可正常处理同一帮派")
    void workerException_releasesLockAndAllowsRetry() throws Exception {
        TornFactionOcDO oc = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc.getId(), USER_ID, "Driver#1", 70, 20000L);

        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("注入的Worker故障");
            }
            return invocation.callRealMethod();
        }).when(transactionWorker).processSingleChain(anyLong(), anyLong(), any(LocalDateTime.class), anySet(), anyList());

        BatchIncomeResult first = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));
        assertNotNull(first);
        assertEquals(1, first.failureCount());
        assertEquals(0, countIncome(oc.getId()));

        // 锁已在finally释放，可直接重新抢占
        assertTrue(batchIncomeService.tryAcquireFactionCalculateLock(FACTION_ID));
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID);

        BatchIncomeResult second = batchIncomeService.batchCalculateIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));
        assertNotNull(second);
        assertEquals(1, second.successCount());
        assertEquals(1, countIncome(oc.getId()));
        assertEquals(1L, countSummary(USER_ID));
    }

    @Test
    @DisplayName("运行期间连续触发多页，最终只合并为一次重跑")
    void requestBatchIncome_triggersDuringRun_mergeToOneRerun() throws Exception {
        TornFactionOcDO oc1 = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc1.getId(), USER_ID, "Driver#1", 70, 20000L);

        // 第一线程进入Worker并阻塞；运行期间多个页触发应合并为一次重跑
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger workerCalls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = workerCalls.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(transactionWorker).processSingleChain(anyLong(), anyLong(), any(LocalDateTime.class), anySet(), anyList());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        // 第一线程真正执行批次
        Future<?> runner = pool.submit(() ->
                batchIncomeService.requestBatchIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        // 运行期间连续触发多页：全部合并为一个重跑请求
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> triggers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            triggers.add(pool.submit(() -> {
                go.await();
                batchIncomeService.requestBatchIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0));
                return null;
            }));
        }
        go.countDown();
        for (Future<?> trigger : triggers) {
            trigger.get(10, TimeUnit.SECONDS);
        }

        // 最后一页OC在锁被占用时提交，只有重跑才能处理它
        TornFactionOcDO lastPage = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 16, 10, 0), 500000L);
        createSlot(lastPage.getId(), USER_ID, "Driver#1", 70, 20000L);

        // 释放第一线程，批次结束后只执行一次最终重跑（Worker总调用为2次：首次+唯一一次重跑）
        release.countDown();
        runner.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(2, workerCalls.get());
        assertEquals(1, countIncome(oc1.getId()));
        assertEquals(1, countIncome(lastPage.getId()));
        assertEquals(1L, countSummary(USER_ID));
    }

    @Test
    @DisplayName("运行期间到达的触发在批次结束后仍被消费，最后一页OC最终生成收益")
    void requestBatchIncome_triggerArrivingDuringRun_finalRerunPicksUpLastPage() throws Exception {
        // 第一页OC
        TornFactionOcDO first = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(first.getId(), USER_ID, "Driver#1", 70, 20000L);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger workerCalls = new AtomicInteger();
        doAnswer(invocation -> {
            workerCalls.incrementAndGet();
            if (workerCalls.get() == 1) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(transactionWorker).processSingleChain(anyLong(), anyLong(), any(LocalDateTime.class), anySet(), anyList());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> runner = pool.submit(() ->
                batchIncomeService.requestBatchIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        // 最后一页OC在锁被占用时提交，触发被合并；释放后重跑必须覆盖它
        TornFactionOcDO lastPage = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 16, 10, 0), 500000L);
        createSlot(lastPage.getId(), USER_ID, "Driver#1", 70, 20000L);
        pool.submit(() -> batchIncomeService.requestBatchIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));

        release.countDown();
        runner.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(2, workerCalls.get());
        assertEquals(1, countIncome(first.getId()));
        assertEquals(1, countIncome(lastPage.getId()));
    }

    @Test
    @DisplayName("重跑期间再次触发仍最终收敛，不会递归失控")
    void requestBatchIncome_triggerDuringRerun_converges() throws Exception {
        TornFactionOcDO oc = createOc(TornConstants.OC_NAME_ACE_IN_THE_HOLE, 9, TornOcStatusEnum.SUCCESSFUL,
                LocalDateTime.of(2026, 4, 15, 10, 0), 500000L);
        createSlot(oc.getId(), USER_ID, "Driver#1", 70, 20000L);

        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        AtomicInteger workerCalls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = workerCalls.incrementAndGet();
            if (call == 1) {
                firstEntered.countDown();
                assertTrue(firstRelease.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(transactionWorker).processSingleChain(anyLong(), anyLong(), any(LocalDateTime.class), anySet(), anyList());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> runner = pool.submit(() ->
                batchIncomeService.requestBatchIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS));

        // 第一次运行期间触发一次重跑
        pool.submit(() -> batchIncomeService.requestBatchIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
        Thread.sleep(200);
        firstRelease.countDown();
        // 重跑期间再次触发，也应被后续收敛消费，不会无限循环
        Thread.sleep(300);
        pool.submit(() -> batchIncomeService.requestBatchIncome(FACTION_ID, LocalDateTime.of(2026, 4, 10, 0, 0, 0)));
        Thread.sleep(300);
        pool.shutdown();

        // 最终收敛：收益只生成一套
        assertEquals(1, countIncome(oc.getId()));
        assertEquals(1L, countSummary(USER_ID));
    }

    private long countSummary(Long userId) {
        return incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getUserId, userId)
                .count();
    }

    private long countIncome(Long ocId) {
        return countIncomeForFaction(ocId, FACTION_ID);
    }

    private long countIncomeForFaction(Long ocId, Long factionId) {
        return incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getFactionId, factionId)
                .eq(TornFactionOcIncomeDO::getOcId, ocId)
                .count();
    }

    private TornFactionOcDO createOc(String name, Integer rank, TornOcStatusEnum status,
                                     LocalDateTime executedTime, Long rewardMoney) {
        return createOcForFaction(name, rank, status, executedTime, rewardMoney, FACTION_ID);
    }

    private TornFactionOcDO createOcForFaction(String name, Integer rank, TornOcStatusEnum status,
                                               LocalDateTime executedTime, Long rewardMoney, Long factionId) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setFactionId(factionId);
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
        createSlotForFaction(ocId, userId, position, passRate, itemValue);
    }

    private void createSlotForFaction(Long ocId, Long userId, String position, Integer passRate, Long itemValue) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        slot.setPassRate(passRate);
        slot.setOutcomeItemValue(itemValue);
        slotDao.save(slot);
    }
}
