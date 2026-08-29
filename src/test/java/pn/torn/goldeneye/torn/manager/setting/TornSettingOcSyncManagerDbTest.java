package pn.torn.goldeneye.torn.manager.setting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC设置目录自动同步真实PostgreSQL集成测试。
 *
 * <p>仅验证不可由Mockito证明的写入事务语义：自增主键插入、重复同步幂等且不覆盖人工调整、
 * 岗位插入异常时OC整体回滚、同JVM并发同步收敛为单条目录。
 * 使用测试专用OC名称前缀，{@code @AfterEach}先物理DELETE岗位再DELETE OC；
 * 不以{@code @Rollback}代替跨提交缓存/事务语义验证。</p>
 *
 * @author Bai
 * @version 1.5.1
 * @since 2026.08.29
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("OC设置目录自动同步真实PostgreSQL集成测试")
class TornSettingOcSyncManagerDbTest {
    /**
     * 测试专用OC名称前缀(远离生产目录名称空间)
     */
    private static final String TEST_OC_PREFIX = "ZZ AUTO SYNC TEST OC ";
    /**
     * 测试专用rank(远离生产目录rank区间)
     */
    private static final int TEST_OC_RANK = 12;

    @Autowired
    private TornSettingOcSyncManager syncManager;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanupTestCatalog() {
        Map<String, Object> param = Map.of("prefix", TEST_OC_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM torn_setting_oc_slot WHERE oc_name LIKE :prefix", param);
        jdbcTemplate.update("DELETE FROM torn_setting_oc WHERE oc_name LIKE :prefix", param);
    }

    @Test
    @DisplayName("首次同步: 插入OC与全部去重岗位且使用数据库自增ID")
    void firstSync_insertsOcAndSlotsWithAutoIncrementIds() {
        String ocName = TEST_OC_PREFIX + "FIRST";

        syncManager.syncMissingAvailable(List.of(TornSettingOcSyncManagerTest.crime(ocName, TEST_OC_RANK,
                TornSettingOcSyncManagerTest.slot("MUS", 1),
                TornSettingOcSyncManagerTest.slot("MUS", 2),
                TornSettingOcSyncManagerTest.slot("WEA", 1))));

        Map<String, Object> ocRow = jdbcTemplate.queryForMap(
                "SELECT id, rank, required_members, prepare_days, expected_reward, deleted "
                        + "FROM torn_setting_oc WHERE oc_name = :name", Map.of("name", ocName));
        assertTrue(((Number) ocRow.get("id")).longValue() > 0, "OC目录id必须由数据库自增生成");
        assertEquals(TEST_OC_RANK, ((Number) ocRow.get("rank")).intValue());
        assertEquals(3, ((Number) ocRow.get("required_members")).intValue());
        assertEquals(3, ((Number) ocRow.get("prepare_days")).intValue());
        assertEquals(0L, ((Number) ocRow.get("expected_reward")).longValue());
        assertEquals(0, ((Number) ocRow.get("deleted")).intValue());

        List<Map<String, Object>> slots = jdbcTemplate.queryForList(
                "SELECT id, slot_code, slot_short_code, pass_rate, priority, best_success, deleted "
                        + "FROM torn_setting_oc_slot WHERE oc_name = :name ORDER BY slot_code",
                Map.of("name", ocName));
        assertEquals(3, slots.size());
        assertTrue(slots.stream().allMatch(s -> ((Number) s.get("id")).longValue() > 0), "岗位id必须由数据库自增生成");
        assertEquals(List.of("MUS#1", "MUS#2", "WEA#1"),
                slots.stream().map(s -> s.get("slot_code")).toList());
        assertTrue(slots.stream().allMatch(s -> ((Number) s.get("pass_rate")).intValue() == 60
                && ((Number) s.get("priority")).intValue() == 0
                && ((Number) s.get("best_success")).doubleValue() == 0.0
                && ((Number) s.get("deleted")).intValue() == 0));
    }

    @Test
    @DisplayName("重复同步: 目录行数保持不变且不覆盖人工调整字段")
    void repeatSync_keepsRowCountAndManualAdjustments() {
        String ocName = TEST_OC_PREFIX + "REPEAT";
        List<TornFactionCrimeVO> crimes = List.of(TornSettingOcSyncManagerTest.crime(ocName, TEST_OC_RANK,
                TornSettingOcSyncManagerTest.slot("MUS", 1),
                TornSettingOcSyncManagerTest.slot("WEA", 1)));
        syncManager.syncMissingAvailable(crimes);

        jdbcTemplate.update(
                "UPDATE torn_setting_oc SET expected_reward = 555 WHERE oc_name = :name", Map.of("name", ocName));
        jdbcTemplate.update(
                "UPDATE torn_setting_oc_slot SET pass_rate = 88, priority = 3 "
                        + "WHERE oc_name = :name AND slot_code = :code",
                Map.of("name", ocName, "code", "MUS#1"));

        syncManager.syncMissingAvailable(crimes);

        assertEquals(1, countRows("torn_setting_oc", ocName));
        assertEquals(2, countRows("torn_setting_oc_slot", ocName));
        Map<String, Object> ocRow = jdbcTemplate.queryForMap(
                "SELECT expected_reward, required_members FROM torn_setting_oc WHERE oc_name = :name",
                Map.of("name", ocName));
        assertEquals(555L, ((Number) ocRow.get("expected_reward")).longValue(), "人工调整的预期收益不得被覆盖");
        assertEquals(2, ((Number) ocRow.get("required_members")).intValue());
        Map<String, Object> slotRow = jdbcTemplate.queryForMap(
                "SELECT pass_rate, priority FROM torn_setting_oc_slot "
                        + "WHERE oc_name = :name AND slot_code = :code",
                Map.of("name", ocName, "code", "MUS#1"));
        assertEquals(88, ((Number) slotRow.get("pass_rate")).intValue(), "人工调整的成功率不得被覆盖");
        assertEquals(3, ((Number) slotRow.get("priority")).intValue(), "人工调整的权重不得被覆盖");
    }

    @Test
    @DisplayName("岗位插入异常: 已插入的OC目录随事务整体回滚")
    void slotInsertFailure_rollsBackOcInsert() {
        String ocName = TEST_OC_PREFIX + "ROLLBACK";
        List<TornFactionCrimeVO> crimes = List.of(TornSettingOcSyncManagerTest.crime(ocName, TEST_OC_RANK,
                TornSettingOcSyncManagerTest.slot("L".repeat(20), 1),
                TornSettingOcSyncManagerTest.slot("MUS", 1)));

        assertThrows(RuntimeException.class, () -> syncManager.syncMissingAvailable(crimes),
                "岗位短编码超过varchar(16)时真实PostgreSQL必须拒绝写入");

        assertEquals(0, countRows("torn_setting_oc", ocName), "OC主目录必须随事务回滚不留半成品");
        assertEquals(0, countRows("torn_setting_oc_slot", ocName));
    }

    @Test
    @DisplayName("同JVM并发同步: 最终只有一条OC目录和一组完整岗位")
    void concurrentSync_singleOcCatalogAndFullSlots() throws Exception {
        String ocName = TEST_OC_PREFIX + "CONCURRENT";
        List<TornFactionCrimeVO> crimes = List.of(TornSettingOcSyncManagerTest.crime(ocName, TEST_OC_RANK,
                TornSettingOcSyncManagerTest.slot("MUS", 1),
                TornSettingOcSyncManagerTest.slot("WEA", 1),
                TornSettingOcSyncManagerTest.slot("HND", 1),
                TornSettingOcSyncManagerTest.slot("MTH", 1)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<?> first = pool.submit(() -> syncConcurrently(crimes, ready, go, failure));
            Future<?> second = pool.submit(() -> syncConcurrently(crimes, ready, go, failure));
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertNull(failure.get(), () -> "并发目录同步不应泄漏任何异常: " + failure.get());
        assertEquals(1, countRows("torn_setting_oc", ocName), "并发后OC目录必须仅一条");
        assertEquals(4, countRows("torn_setting_oc_slot", ocName), "并发后岗位目录必须完整且唯一");
    }

    /**
     * 屏障对齐后通过真实Spring代理事务执行目录同步，记录可能发生的异常。
     *
     * @param crimes  available OC列表
     * @param ready   就绪屏障
     * @param go      开跑屏障
     * @param failure 异常收集
     * @return 恒为null
     */
    private Void syncConcurrently(List<TornFactionCrimeVO> crimes, CountDownLatch ready, CountDownLatch go,
                                  AtomicReference<Throwable> failure) {
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, e);
            return null;
        }
        try {
            syncManager.syncMissingAvailable(crimes);
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
        return null;
    }

    /**
     * 统计指定表内测试OC名称的行数。
     *
     * @param table  表名
     * @param ocName OC名称
     * @return 行数
     */
    private int countRows(String table, String ocName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE oc_name = :name", Map.of("name", ocName), Integer.class);
    }
}
