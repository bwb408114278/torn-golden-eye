package pn.torn.goldeneye.torn.service.activity.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRangeModeEnum;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活跃度热力图日期参数解析纯函数测试
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@DisplayName("活跃度热力图日期参数解析纯函数测试")
class ActivityQueryRangeParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    @Test
    @DisplayName("无日期参数应解析为最近 28 天闭区间（DEFAULT）")
    void shouldParseEmptyTailAsDefaultRange() {
        ActivityQueryRange range = assertPresent(ActivityQueryRangeParser.parse(List.of(), TODAY));

        assertEquals(ActivityQueryRangeModeEnum.DEFAULT, range.mode());
        assertEquals(LocalDate.of(2026, 8, 1), range.startDate());
        assertEquals(TODAY, range.endDate());
        assertEquals(28, range.totalDays());
    }

    @Test
    @DisplayName("null 尾部参数等价默认 28 天")
    void shouldParseNullTailAsDefaultRange() {
        ActivityQueryRange range = assertPresent(ActivityQueryRangeParser.parse(null, TODAY));

        assertEquals(ActivityQueryRangeModeEnum.DEFAULT, range.mode());
    }

    @Test
    @DisplayName("从#日期 应解析为 [startDate, 今天]（FROM）")
    void shouldParseFromKeyword() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parse(List.of("从", "2026-08-01"), TODAY));

        assertEquals(ActivityQueryRangeModeEnum.FROM, range.mode());
        assertEquals(LocalDate.of(2026, 8, 1), range.startDate());
        assertEquals(TODAY, range.endDate());
    }

    @Test
    @DisplayName("从#今天 边界合法（单日范围）")
    void shouldParseFromTodayAsSingleDay() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parse(List.of("从", "2026-08-28"), TODAY));

        assertEquals(TODAY, range.startDate());
        assertEquals(TODAY, range.endDate());
        assertEquals(1, range.totalDays());
    }

    @Test
    @DisplayName("截至#日期 应解析为 [endDate - 27 天, endDate]（UNTIL）")
    void shouldParseUntilKeyword() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parse(List.of("截至", "2026-08-01"), TODAY));

        assertEquals(ActivityQueryRangeModeEnum.UNTIL, range.mode());
        assertEquals(LocalDate.of(2026, 7, 5), range.startDate());
        assertEquals(LocalDate.of(2026, 8, 1), range.endDate());
    }

    @Test
    @DisplayName("截至#今天 边界合法")
    void shouldParseUntilToday() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parse(List.of("截至", "2026-08-28"), TODAY));

        assertEquals(ActivityQueryRangeModeEnum.UNTIL, range.mode());
        assertEquals(TODAY, range.endDate());
    }

    @Test
    @DisplayName("未来日期应拒绝，不自动截断到今天")
    void shouldRejectFutureDates() {
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2026-08-29"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("截至", "2026-08-29"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2999-01-01"), TODAY).isEmpty());
    }

    @Test
    @DisplayName("非 yyyy-MM-dd 严格格式应拒绝")
    void shouldRejectNonStrictDateFormats() {
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2026/08/01"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2026-8-1"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", " 2026-08-01"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2026-08-01 00:00:00"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", ""), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2026-02-30"), TODAY).isEmpty());
    }

    @Test
    @DisplayName("错误关键字应拒绝")
    void shouldRejectWrongKeyword() {
        assertTrue(ActivityQueryRangeParser.parse(List.of("自从", "2026-08-01"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("到", "2026-08-01"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("", "2026-08-01"), TODAY).isEmpty());
    }

    @Test
    @DisplayName("关键字重复或同时出现应拒绝")
    void shouldRejectDuplicatedKeywords() {
        assertTrue(ActivityQueryRangeParser.parse(
                List.of("从", "2026-08-01", "从", "2026-08-02"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(
                List.of("从", "2026-08-01", "截至", "2026-08-02"), TODAY).isEmpty());
    }

    @Test
    @DisplayName("参数段数不符应拒绝")
    void shouldRejectInvalidSegmentCount() {
        assertTrue(ActivityQueryRangeParser.parse(List.of("从"), TODAY).isEmpty());
        assertTrue(ActivityQueryRangeParser.parse(List.of("从", "2026-08-01", "多余"), TODAY).isEmpty());
    }

    @Test
    @DisplayName("范围关键字允许首尾空白")
    void shouldTrimKeywordWhitespace() {
        ActivityQueryRange range = assertPresent(
                ActivityQueryRangeParser.parse(List.of(" 从 ", "2026-08-01"), TODAY));

        assertEquals(ActivityQueryRangeModeEnum.FROM, range.mode());
    }

    private static ActivityQueryRange assertPresent(Optional<ActivityQueryRange> parsed) {
        assertTrue(parsed.isPresent(), "合法参数应解析成功");
        return parsed.get();
    }
}
