package pn.torn.goldeneye.repository.mapper.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.activity.TornActivityArchiveDayDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityFactionDailyDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityUserDailyDAO;
import pn.torn.goldeneye.repository.model.activity.TornActivityFactionDailyDO;
import pn.torn.goldeneye.repository.model.activity.TornActivityUserDailyDO;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 活跃度V3日终归档 Mapper 真实 PostgreSQL 测试
 * <p>
 * 验证用户/帮派日包"首次写入 → 同业务键重试覆盖 → 范围读取"的实际 UPSERT SQL 行为，
 * 以及归档 marker 的幂等写入与日期范围查询。数据使用 2099 严格未来日期与 99.7M
 * 测试专用 ID 命名空间隔离真实归档数据，类级事务以{@code @Rollback}回滚，测试库零残留。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("活跃度V3日终归档Mapper真实PostgreSQL测试")
@Transactional
@Rollback
class ActivityDailyArchiveMapperTest {

    /**
     * 隔离测试自然日（2099 严格未来时间命名空间，远离生产数据）
     */
    private static final LocalDate DAY1 = LocalDate.of(2099, 10, 1);
    private static final LocalDate DAY2 = LocalDate.of(2099, 10, 2);

    /**
     * 测试专用用户 ID（远离真实 Torn 用户 ID 段）
     */
    private static final long USER_ID = 99700001L;

    /**
     * 测试专用帮派 ID
     */
    private static final long FACTION_ID = 99700002L;

    @Autowired
    private TornActivityUserDailyDAO userDailyDao;
    @Autowired
    private TornActivityFactionDailyDAO factionDailyDao;
    @Autowired
    private TornActivityArchiveDayDAO archiveDayDao;

    @Test
    @DisplayName("真实PG_用户日包首次写入→同业务键重试覆盖→范围读取")
    void upsertBatch_userDaily_insertOverwriteAndRangeRead() {
        assertEquals(2, userDailyDao.upsertBatch(List.of(
                userDaily(DAY1, bytes(0xF0), bytes(0xC0), bytes(0x20)),
                userDaily(DAY2, bytes(0x80), bytes(0x80), bytes(0x00)))));

        List<TornActivityUserDailyDO> firstRead = userDailyDao.selectByUserAndDateRange(
                USER_ID, DAY1, DAY2);
        assertEquals(2, firstRead.size(), "范围读取应命中两日日包");
        assertEquals(DAY1, firstRead.get(0).getActivityDate(), "范围读取按 activity_date 升序");
        assertEquals(DAY2, firstRead.get(1).getActivityDate());
        assertArrayEquals(bytes(0xF0), firstRead.get(0).getObservedBitmap(), "BYTEA Bitmap 须无损往返");

        int overwritten = userDailyDao.upsertBatch(List.of(userDaily(DAY1, bytes(0x0F), bytes(0x0F), bytes(0x00))));
        assertEquals(1, overwritten, "同业务键重试应更新既有行");

        List<TornActivityUserDailyDO> secondRead = userDailyDao.selectByUserAndDateRange(
                USER_ID, DAY1, DAY1);
        assertEquals(1, secondRead.size(), "重试不得产生重复日包");
        assertArrayEquals(bytes(0x0F), secondRead.getFirst().getObservedBitmap(), "重试应覆盖为当前 Redis 完整 V3 包");
        assertEquals("V3", secondRead.getFirst().getDataVersion());
        assertNotNull(secondRead.getFirst().getId(), "落库主键非空");

        assertTrue(userDailyDao.selectByUserAndDateRange(USER_ID, DAY2.plusDays(1), DAY2.plusDays(5)).isEmpty(),
                "范围外日期不得读取到该用户数据");
    }

    @Test
    @DisplayName("真实PG_帮派日包首次写入→同业务键重试覆盖→范围读取")
    void upsertBatch_factionDaily_insertOverwriteAndRangeRead() {
        assertEquals(1, factionDailyDao.upsertBatch(List.of(
                factionDaily(DAY1, bytes(0xF0), slotBytes(10, 20), slotBytes(0, 5), slotBytes(90, 90)))));

        List<TornActivityFactionDailyDO> firstRead = factionDailyDao.selectByFactionAndDateRange(
                FACTION_ID, DAY1, DAY2);
        assertEquals(1, firstRead.size());
        assertArrayEquals(slotBytes(10, 20), firstRead.getFirst().getActiveCounts(), "槽值 BYTEA 须无损往返");
        assertArrayEquals(slotBytes(0, 5), firstRead.getFirst().getIdleCounts());
        assertArrayEquals(slotBytes(90, 90), firstRead.getFirst().getMemberCounts());

        assertEquals(1, factionDailyDao.upsertBatch(List.of(
                factionDaily(DAY1, bytes(0x80), slotBytes(33, 0), slotBytes(0, 0), slotBytes(77, 77)))));

        List<TornActivityFactionDailyDO> secondRead = factionDailyDao.selectByFactionAndDateRange(
                FACTION_ID, DAY1, DAY1);
        assertEquals(1, secondRead.size(), "重试不得产生重复日包");
        assertArrayEquals(slotBytes(33, 0), secondRead.getFirst().getActiveCounts(), "重试应覆盖为当前完整 V3 包");
        assertEquals("V3", secondRead.getFirst().getDataVersion());
    }

    @Test
    @DisplayName("真实PG_marker幂等写入与已归档日期范围查询")
    void insertMarker_idempotentAndRangeQuery() {
        archiveDayDao.insertMarker(DAY1);

        assertTrue(archiveDayDao.selectArchivedDates(DAY1, DAY2).contains(DAY1),
                "写入后应能查询到已归档日期");

        archiveDayDao.insertMarker(DAY1);

        Set<LocalDate> archived = archiveDayDao.selectArchivedDates(DAY1, DAY2);
        assertEquals(1, archived.size(), "重复写 marker 不得产生重复完成状态");
        assertFalse(archiveDayDao.selectArchivedDates(DAY2, DAY2).contains(DAY2), "范围查询不得越界");
    }

    private static TornActivityUserDailyDO userDaily(LocalDate date, byte[] observed, byte[] active, byte[] idle) {
        TornActivityUserDailyDO row = new TornActivityUserDailyDO();
        row.setUserId(USER_ID);
        row.setActivityDate(date);
        row.setObservedBitmap(observed);
        row.setActiveBitmap(active);
        row.setIdleBitmap(idle);
        row.setDataVersion("V3");
        return row;
    }

    private static TornActivityFactionDailyDO factionDaily(LocalDate date, byte[] observed,
                                                           byte[] activeCounts, byte[] idleCounts,
                                                           byte[] memberCounts) {
        TornActivityFactionDailyDO row = new TornActivityFactionDailyDO();
        row.setFactionId(FACTION_ID);
        row.setActivityDate(date);
        row.setObservedBitmap(observed);
        row.setActiveCounts(activeCounts);
        row.setIdleCounts(idleCounts);
        row.setMemberCounts(memberCounts);
        row.setDataVersion("V3");
        return row;
    }

    /**
     * 构造 12 字节 Bitmap，首字节为给定值
     */
    private static byte[] bytes(int firstByte) {
        byte[] data = new byte[12];
        data[0] = (byte) firstByte;
        return data;
    }

    /**
     * 构造 96 字节槽值数组，前两槽使用给定值
     */
    private static byte[] slotBytes(int first, int second) {
        byte[] data = new byte[96];
        data[0] = (byte) first;
        data[1] = (byte) second;
        return data;
    }
}
