package pn.torn.goldeneye.torn.service.activity.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.activity.ActivityCaliberEnum;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活跃度热力图尾部参数解析纯函数测试
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.08.28
 */
@DisplayName("活跃度热力图尾部参数解析纯函数测试")
class ActivityQueryRangeParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    // ==================== 普通热力图形态 ====================

    @Test
    @DisplayName("无参数应解析为典型周近 28 天")
    void shouldParseEmptyTailAsTypicalWeek() {
        ActivityQueryRange range = assertPresent(ActivityQueryRangeParser.parse(List.of(), TODAY));

        assertEquals(ActivityCaliberEnum.TYPICAL_WEEK, range.caliber());
        assertEquals(LocalDate.of(2026, 9, 6), range.startDate());
        assertEquals(TODAY, range.endDate());
        assertEquals(28, range.totalDays());
    }

    @Test
    @DisplayName("null 尾部参数等价典型周")
    void shouldParseNullTailAsTypicalWeek() {
        ActivityQueryRange range = assertPresent(ActivityQueryRangeParser.parse(null, TODAY));

        assertEquals(ActivityCaliberEnum.TYPICAL_WEEK, range.caliber());
    }

    @Test
    @DisplayName("只给日期即该日单日，不再是截至该日的近 28 天")
    void shouldParseSingleDateAsSingleDay() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parse(List.of("2026-09-28"), TODAY));

        assertEquals(ActivityCaliberEnum.SINGLE_DAY, range.caliber());
        assertEquals(LocalDate.of(2026, 9, 28), range.startDate());
        assertEquals(LocalDate.of(2026, 9, 28), range.endDate());
        assertEquals(1, range.totalDays());
    }

    @Test
    @DisplayName("只给口径时以今天为锚点")
    void shouldParseCaliberOnlyWithTodayAnchor() {
        assertCaliberRange(List.of("单日"), ActivityCaliberEnum.SINGLE_DAY, TODAY, TODAY);
        assertCaliberRange(List.of("单周"), ActivityCaliberEnum.SINGLE_WEEK,
                TODAY.minusDays(6), TODAY);
        assertCaliberRange(List.of("半月"), ActivityCaliberEnum.HALF_MONTH,
                TODAY.minusDays(14), TODAY);
        assertCaliberRange(List.of("典型周"), ActivityCaliberEnum.TYPICAL_WEEK,
                TODAY.minusDays(27), TODAY);
    }

    @Test
    @DisplayName("日期 + 口径 与 口径 + 日期 等价")
    void shouldParseDateAndCaliberInAnyOrder() {
        assertCaliberRange(List.of("2026-09-28", "单周"), ActivityCaliberEnum.SINGLE_WEEK,
                LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 28));
        assertCaliberRange(List.of("单周", "2026-09-28"), ActivityCaliberEnum.SINGLE_WEEK,
                LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 28));
        assertCaliberRange(List.of("2026-09-28", "半月"), ActivityCaliberEnum.HALF_MONTH,
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("锚点为今天时窗口以今天结束")
    void shouldParseTodayAsAnchor() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parse(List.of("2026-10-03", "单周"), TODAY));

        assertEquals(ActivityCaliberEnum.SINGLE_WEEK, range.caliber());
        assertEquals(LocalDate.of(2026, 9, 27), range.startDate());
        assertEquals(TODAY, range.endDate());
    }

    @Test
    @DisplayName("未来日期、未知关键字、同类重复、超过两段与空段一律拒绝")
    void shouldRejectInvalidPlainTails() {
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-10-04"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-10-04", "单日"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2026-09-28"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-09-28", "截至"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("单日", "单周"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-09-28", "2026-09-29"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-09-28", "单日", "单周"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of(""), TODAY).isEmpty());
    }

    @Test
    @DisplayName("非 yyyy-MM-dd 严格格式应拒绝")
    void shouldRejectNonStrictDateFormats() {
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026/09/28"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-9-28"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of(" 2026-09-28"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-09-28 12:00:00"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("2026-02-30"), TODAY).isEmpty());
    }

    // ==================== 对比图形态 ====================

    @Test
    @DisplayName("对比形态无参数为以今天为锚点的近 28 天")
    void shouldParseEmptyCompareTail() {
        ActivityQueryRange range = assertPresent(ActivityQueryRangeParser.parseUntilDate(List.of(), TODAY));

        assertEquals(ActivityCaliberEnum.TYPICAL_WEEK, range.caliber());
        assertEquals(TODAY.minusDays(27), range.startDate());
        assertEquals(TODAY, range.endDate());
        assertEquals(ActivityCaliberEnum.TYPICAL_WEEK,
                assertPresent(ActivityQueryRangeParser.parseUntilDate(null, TODAY)).caliber());
    }

    @Test
    @DisplayName("对比形态单个截至日期解析为以该日为锚点的近 28 天")
    void shouldParseSingleUntilDate() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parseUntilDate(List.of("2026-09-28"), TODAY));

        assertEquals(ActivityCaliberEnum.TYPICAL_WEEK, range.caliber());
        assertEquals(LocalDate.of(2026, 9, 1), range.startDate());
        assertEquals(LocalDate.of(2026, 9, 28), range.endDate());
        assertEquals(28, range.totalDays());
    }

    @Test
    @DisplayName("对比形态拒绝口径关键字、多段、未来与非法日期")
    void shouldRejectInvalidCompareTails() {
        assertTrue(ActivityQueryRangeParser.parseUntilDate(List.of("单日"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parseUntilDate(List.of("2026-09-28", "单周"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parseUntilDate(List.of("2026-10-04"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parseUntilDate(List.of("2026/09/28"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parseUntilDate(List.of(""), TODAY).isEmpty());
    }

    private static void assertCaliberRange(List<String> tailSegments, ActivityCaliberEnum caliber,
                                           LocalDate startDate, LocalDate endDate) {
        ActivityQueryRange range = assertPresent(ActivityQueryRangeParser.parse(tailSegments, TODAY));

        assertEquals(caliber, range.caliber(), "口径应解析为 " + caliber);
        assertEquals(startDate, range.startDate(), "窗口起始日");
        assertEquals(endDate, range.endDate(), "窗口结束日");
        assertEquals(caliber.windowDays(), range.totalDays(), "窗口长度由口径唯一决定");
    }

    private static ActivityQueryRange assertPresent(Optional<ActivityQueryRange> parsed) {
        assertTrue(parsed.isPresent(), "合法参数应解析成功");
        return parsed.get();
    }
}
