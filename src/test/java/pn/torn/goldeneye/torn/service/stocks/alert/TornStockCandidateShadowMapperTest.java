package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 候选影子账本真实PostgreSQL集成测试。
 * <p>
 * 使用远端未来股票ID与批次号前缀作为隔离数据,{@code @AfterEach}在每次测试后按本测试
 * 专用前缀(C2099/F2099/S2099)精确物理DELETE,开发库零残留(不以{@code @Rollback}
 * 代替提交态验证)。验证:
 * <ul>
 *   <li>候选影子同股部分唯一索引: 同股活跃候选影子批次只能一行,重复插入被数据库拒绝;</li>
 *   <li>候选影子同槽部分唯一索引: 同slot活跃候选影子批次只能一行;</li>
 *   <li>两个独立事务并发插入同股候选影子,恰好一个提交成功、一个被唯一索引拒绝;</li>
 *   <li>候选影子与正式账本唯一约束完全隔离: 同股正式与候选影子可同时存在;</li>
 *   <li>无限资金影子不受候选影子容量影响。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@SpringBootTest
@DisplayName("候选影子账本真实PostgreSQL集成测试")
class TornStockCandidateShadowMapperTest {

    @Autowired
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 隔离股票ID(远离生产数据)
     */
    private static final int TEST_STOCK = 2099101;
    /**
     * 隔离股票ID2
     */
    private static final int TEST_STOCK2 = 2099102;
    /**
     * 隔离槽位ID
     */
    private static final long TEST_SLOT = 2099101L;
    /**
     * 隔离批次时间
     */
    private static final LocalDateTime TEST_TIME = LocalDateTime.of(2099, 9, 1, 10, 0);

    @AfterEach
    void cleanupIsolatedBatches() {
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_virtual_batch "
                        + "WHERE batch_no LIKE 'C2099%' OR batch_no LIKE 'F2099%' OR batch_no LIKE 'S2099%'",
                Map.of());
    }

    @Test
    @DisplayName("真实PG_候选影子同股部分唯一索引_重复活跃批次被数据库拒绝")
    void candidateShadowSameStockUnique_rejectsDuplicateActiveBatch() {
        TornStockVirtualBatchDO first = candidateShadowBatch("C20991011", TEST_STOCK, null);
        TornStockVirtualBatchDO duplicate = candidateShadowBatch("C20991012", TEST_STOCK, null);

        virtualBatchDao.save(first);

        assertThrows(DuplicateKeyException.class, () -> virtualBatchDao.save(duplicate),
                "候选影子同股活跃批次重复插入必须被部分唯一索引拒绝");
    }

    @Test
    @DisplayName("真实PG_候选影子同槽部分唯一索引_重复占用槽位被数据库拒绝")
    void candidateShadowSameSlotUnique_rejectsDuplicateSlot() {
        TornStockVirtualBatchDO first = candidateShadowBatch("C20991021", TEST_STOCK, TEST_SLOT);
        TornStockVirtualBatchDO second = candidateShadowBatch("C20991022", TEST_STOCK2, TEST_SLOT);

        virtualBatchDao.save(first);

        assertThrows(DuplicateKeyException.class, () -> virtualBatchDao.save(second),
                "候选影子同槽位活跃批次重复插入必须被部分唯一索引拒绝");
    }

    @Test
    @DisplayName("真实PG_候选影子与正式账本隔离_同股正式与候选影子可同时存在")
    void candidateShadowAndFormal_isolatedSameStockAllowed() {
        TornStockVirtualBatchDO formal = formalBatch("F20991031", TEST_STOCK);
        TornStockVirtualBatchDO candidate = candidateShadowBatch("C20991032", TEST_STOCK, null);

        virtualBatchDao.save(formal);
        virtualBatchDao.save(candidate);

        assertEquals(2, virtualBatchDao.count(),
                "正式与候选影子是独立账本,同股可同时存在活跃批次");
    }

    @Test
    @DisplayName("真实PG_无限资金影子与候选影子互不约束")
    void unlimitedShadowAndCandidateShadow_independent() {
        TornStockVirtualBatchDO unlimited = unlimitedShadowBatch("S20991041", TEST_STOCK);
        TornStockVirtualBatchDO candidate = candidateShadowBatch("C20991042", TEST_STOCK, null);

        virtualBatchDao.save(unlimited);
        virtualBatchDao.save(candidate);

        assertEquals(2, virtualBatchDao.count(),
                "无限资金影子与候选影子是独立账本,可同股同时存在");
    }

    @Test
    @DisplayName("真实PG_两个独立事务并发插入同股候选影子_仅一个活跃批次提交成功")
    void candidateShadowSameStock_concurrentInserts_onlyOneActiveBatchCommitted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger duplicateFailureCount = new AtomicInteger();
        Future<?> first = pool.submit(() ->
                insertCandidateConcurrently("C20991051", successCount, duplicateFailureCount, ready, go));
        Future<?> second = pool.submit(() ->
                insertCandidateConcurrently("C20991052", successCount, duplicateFailureCount, ready, go));
        ready.await();
        go.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, successCount.get(), "同股候选影子并发插入必须恰好一个提交成功");
        assertEquals(1, duplicateFailureCount.get(), "同股候选影子并发插入必须恰好一个被唯一索引拒绝");
        assertEquals(1, countActiveCandidate(TEST_STOCK), "提交后同股活跃候选影子批次必须仅一行");
    }

    /**
     * 在独立事务中插入同股候选影子批次,统计提交成功与唯一索引拒绝次数。
     *
     * @param batchNo              批次编号
     * @param successCount         提交成功计数
     * @param duplicateFailureCount 唯一索引拒绝计数
     * @param ready                就绪屏障
     * @param go                   开跑屏障
     * @return 恒为null
     */
    private Void insertCandidateConcurrently(String batchNo, AtomicInteger successCount,
                                             AtomicInteger duplicateFailureCount,
                                             CountDownLatch ready, CountDownLatch go)
            throws InterruptedException {
        ready.countDown();
        go.await();
        try {
            transactionTemplate.executeWithoutResult(status ->
                    virtualBatchDao.save(candidateShadowBatch(batchNo, TEST_STOCK, null)));
            successCount.incrementAndGet();
        } catch (DataIntegrityViolationException e) {
            duplicateFailureCount.incrementAndGet();
        }
        return null;
    }

    /**
     * 统计指定股票活跃候选影子批次行数(与部分唯一索引活跃状态集合一致)。
     *
     * @param stocksId 股票ID
     * @return 活跃候选影子批次行数
     */
    private int countActiveCandidate(int stocksId) {
        return namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torn_stock_virtual_batch "
                        + "WHERE stocks_id = :stock AND ledger_type = :ledger "
                        + "AND batch_status IN ('ENTRY_PENDING','OPEN','DATA_STALE',"
                        + "'EXIT_PENDING','DATA_STALE_EXIT') AND deleted = 0",
                Map.of("stock", stocksId,
                        "ledger", StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode()),
                Integer.class);
    }

    /**
     * 构建候选影子活跃批次DO。
     *
     * @param batchNo  批次编号
     * @param stocksId 股票ID
     * @param slotId   槽位ID(可为null)
     * @return 候选影子批次DO
     */
    private TornStockVirtualBatchDO candidateShadowBatch(String batchNo, int stocksId, Long slotId) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo(batchNo);
        batch.setLedgerType(StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode());
        batch.setStocksId(stocksId);
        batch.setStocksShortname("T" + stocksId);
        batch.setPrimaryStrategy("RANGE");
        batch.setMatchedStrategies("[\"RANGE\"]");
        batch.setQualityScore(new BigDecimal("1.0"));
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSignalEventId(1L);
        batch.setSlotId(slotId);
        batch.setSlotNo(slotId == null ? null : slotId.intValue());
        batch.setSignalTime(TEST_TIME);
        batch.setSignalReferencePrice(new BigDecimal("100.00"));
        batch.setExpectedEntryBarTime(TEST_TIME);
        batch.setEntryStaleAt(TEST_TIME.plusMinutes(35));
        batch.setStylePrior("NARROW");
        batch.setStyleMaturity("M2_PROVISIONAL");
        batch.setRiskLevel("NONE");
        batch.setStyleEffectiveMonth(TEST_TIME.toLocalDate().withDayOfMonth(1));
        batch.setBuyRuleVersion("1.1.0");
        batch.setSellRuleVersion("1.0.0");
        batch.setStyleRuleVersion("1.0.0");
        batch.setRiskRuleVersion("1.0.0");
        batch.setAllocationRuleVersion("1.0.0");
        batch.setMessageRuleVersion("1.0.0");
        batch.setResetObserved(false);
        return batch;
    }

    /**
     * 构建正式活跃批次DO。
     *
     * @param batchNo  批次编号
     * @param stocksId 股票ID
     * @return 正式批次DO
     */
    private TornStockVirtualBatchDO formalBatch(String batchNo, int stocksId) {
        TornStockVirtualBatchDO batch = candidateShadowBatch(batchNo, stocksId, TEST_SLOT + 1);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        return batch;
    }

    /**
     * 构建无限资金影子活跃批次DO。
     *
     * @param batchNo  批次编号
     * @param stocksId 股票ID
     * @return 无限资金影子批次DO
     */
    private TornStockVirtualBatchDO unlimitedShadowBatch(String batchNo, int stocksId) {
        TornStockVirtualBatchDO batch = candidateShadowBatch(batchNo, stocksId, null);
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        return batch;
    }
}
