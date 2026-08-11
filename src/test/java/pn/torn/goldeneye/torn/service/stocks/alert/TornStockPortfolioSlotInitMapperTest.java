package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票组合槽位冲突安全插入真实PostgreSQL集成测试。
 * <p>
 * 验证 {@link TornStockPortfolioSlotDAO#insertSlotsIgnoreConflict} 与部分唯一索引
 * {@code uk_stock_portfolio_slot_code_no(portfolio_code, slot_no WHERE deleted=0)} 同语义的
 * {@code INSERT ... ON CONFLICT DO NOTHING} 行为:
 * <ul>
 *   <li>空表批量插入全部落库,重复执行被唯一索引冲突吸收为0,总行数保持不变;</li>
 *   <li>两个独立事务并发修复同一组合,恰好5行且无异常泄漏;</li>
 *   <li>新插入槽位满足标准初始字段(lock_version=0、slot_status=AVAILABLE、现金=20亿)。</li>
 * </ul>
 * 全部使用隔离组合编码(TEST_FORMAL/TEST_CANDIDATE/TEST_CONCURRENT),{@code @AfterEach}精确物理DELETE
 * 本测试专用编码,开发库零残留。真实VIP组合在开发库已有Liquibase初始化数据,服务级
 * {@code verifyAndInitSlots()} 不触碰真实组合编码,故服务级修复收敛场景在此不重复执行。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@SpringBootTest
@DisplayName("股票组合槽位冲突安全插入真实PostgreSQL集成测试")
class TornStockPortfolioSlotInitMapperTest {

    /**
     * 隔离正式组合编码(远离生产数据)
     */
    private static final String TEST_FORMAL = "TEST_FORMAL";
    /**
     * 隔离候选影子组合编码(远离生产数据)
     */
    private static final String TEST_CANDIDATE = "TEST_CANDIDATE";
    /**
     * 隔离并发修复组合编码(远离生产数据)
     */
    private static final String TEST_CONCURRENT = "TEST_CONCURRENT";
    /**
     * 标准初始现金(20亿)
     */
    private static final BigDecimal STANDARD_CASH = new BigDecimal("2000000000.00");

    @Autowired
    private TornStockPortfolioSlotDAO slotDao;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanupIsolatedSlots() {
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_portfolio_slot WHERE portfolio_code IN (:codes)",
                Map.of("codes", List.of(TEST_FORMAL, TEST_CANDIDATE, TEST_CONCURRENT)));
    }

    @Test
    @DisplayName("真实PG_空表批量插入10个槽位_重复执行冲突吸收不再新增")
    void insertSlotsIgnoreConflict_emptyTable_allTenInsertedThenReRunAbsorbed() {
        int insertedFormal = slotDao.insertSlotsIgnoreConflict(slotsFor(TEST_FORMAL, 1, 5));
        int insertedCandidate = slotDao.insertSlotsIgnoreConflict(slotsFor(TEST_CANDIDATE, 1, 5));

        assertEquals(5, insertedFormal, "正式隔离组合应插入5行");
        assertEquals(5, insertedCandidate, "候选影子隔离组合应插入5行");
        assertEquals(10, countSlots(TEST_FORMAL) + countSlots(TEST_CANDIDATE), "两个组合合计10行");

        int reRun = slotDao.insertSlotsIgnoreConflict(slotsFor(TEST_FORMAL, 1, 5));
        assertEquals(0, reRun, "重复插入与已有有效槽位冲突应被吸收为0");
        assertEquals(10, countSlots(TEST_FORMAL) + countSlots(TEST_CANDIDATE), "重复执行后总行数必须保持10");

        assertStandardFields(TEST_FORMAL);
        assertStandardFields(TEST_CANDIDATE);
    }

    @Test
    @DisplayName("真实PG_并发修复同一组合_恰好5行且无异常泄漏")
    void insertSlotsIgnoreConflict_concurrentRepair_exactlyFiveRowsNoExceptionLeak() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Future<?> first = pool.submit(() -> insertConcurrently(TEST_CONCURRENT, ready, go, failures));
        Future<?> second = pool.submit(() -> insertConcurrently(TEST_CONCURRENT, ready, go, failures));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(failures.isEmpty(), () -> "并发修复不应泄漏任何异常: " + failures);
        assertEquals(5, countSlots(TEST_CONCURRENT), "并发双线程修复后必须恰好5行,不得重复");
        assertStandardFields(TEST_CONCURRENT);
    }

    /**
     * 统计指定组合当前有效槽位行数。
     *
     * @param portfolioCode 组合编码
     * @return 有效槽位行数
     */
    private int countSlots(String portfolioCode) {
        return namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torn_stock_portfolio_slot "
                        + "WHERE portfolio_code = :code AND deleted = 0",
                Map.of("code", portfolioCode),
                Integer.class);
    }

    /**
     * 断言指定组合全部槽位满足标准初始字段(lock_version=0、slot_status=AVAILABLE、现金=20亿)。
     *
     * @param portfolioCode 组合编码
     */
    private void assertStandardFields(String portfolioCode) {
        List<Map<String, Object>> rows = namedJdbcTemplate.queryForList(
                "SELECT lock_version, slot_status, initial_cash, available_cash, reserved_cash "
                        + "FROM torn_stock_portfolio_slot WHERE portfolio_code = :code AND deleted = 0 "
                        + "ORDER BY slot_no",
                Map.of("code", portfolioCode));
        assertEquals(5, rows.size(), "组合应存在5个有效槽位");
        for (Map<String, Object> row : rows) {
            assertEquals(0L, ((Number) row.get("lock_version")).longValue(), "lock_version应初始化为0");
            assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), row.get("slot_status"),
                    "slot_status应为AVAILABLE");
            assertEquals(0, STANDARD_CASH.compareTo((BigDecimal) row.get("initial_cash")),
                    "initial_cash应为20亿");
            assertEquals(0, STANDARD_CASH.compareTo((BigDecimal) row.get("available_cash")),
                    "available_cash应为20亿");
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("reserved_cash")),
                    "reserved_cash应为0");
        }
    }

    /**
     * 在独立事务中并发插入指定组合槽位1~5,捕获并记录异常供断言。
     *
     * @param portfolioCode 组合编码
     * @param ready         就绪屏障
     * @param go            开跑屏障
     * @param failures      异常收集列表
     */
    private void insertConcurrently(String portfolioCode, CountDownLatch ready, CountDownLatch go,
                                    List<Throwable> failures) {
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add(e);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status ->
                    slotDao.insertSlotsIgnoreConflict(slotsFor(portfolioCode, 1, 5)));
        } catch (Exception e) {
            failures.add(e);
        }
    }

    /**
     * 构建指定组合闭区间槽位序号的标准初始槽位列表。
     *
     * @param portfolioCode 组合编码
     * @param fromNo        起始槽位序号(含)
     * @param toNo          结束槽位序号(含)
     * @return 标准初始槽位列表
     */
    private List<TornStockPortfolioSlotDO> slotsFor(String portfolioCode, int fromNo, int toNo) {
        return IntStream.rangeClosed(fromNo, toNo)
                .mapToObj(slotNo -> buildStandardSlot(portfolioCode, slotNo))
                .toList();
    }

    /**
     * 构建指定组合的一个标准初始槽位DO。
     *
     * @param portfolioCode 组合编码
     * @param slotNo        槽位序号
     * @return 标准初始槽位DO
     */
    private TornStockPortfolioSlotDO buildStandardSlot(String portfolioCode, int slotNo) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setPortfolioCode(portfolioCode);
        slot.setSlotNo(slotNo);
        slot.setInitialCash(STANDARD_CASH);
        slot.setAvailableCash(STANDARD_CASH);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(null);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        slot.setLockVersion(0L);
        return slot;
    }
}
