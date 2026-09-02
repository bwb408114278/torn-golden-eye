package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageTimeStatusEnum;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC图片标题时间状态格式化测试。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("OC图片标题时间格式化测试")
class OcImageTitleFormatterTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0, 30);
    private final OcImageTitleFormatter formatter = new OcImageTitleFormatter();

    @Test
    @DisplayName("无人OC不追加标题时间文案")
    void readyTimeNull_shouldReturnEmptyText() {
        assertEquals("", formatter.format("Recruiting", null, NOW));
    }

    @Test
    @DisplayName("Recruiting未超过24小时显示停转倒计时")
    void recruitingWithin24Hours_shouldShowStopCountdown() {
        assertEquals("1小时30分后停转",
                formatter.format("Recruiting", NOW.plusHours(1).plusMinutes(30), NOW));
    }

    @Test
    @DisplayName("Recruiting恰好24小时仍显示停转倒计时")
    void recruitingExactly24Hours_shouldShowStopCountdown() {
        assertEquals("24小时00分后停转",
                formatter.format("Recruiting", NOW.plusHours(24), NOW));
    }

    @Test
    @DisplayName("Recruiting超过24小时显示空转时间")
    void recruitingOver24Hours_shouldShowIdleText() {
        assertEquals("还需空转24小时00分钟",
                formatter.format("Recruiting", NOW.plusHours(24).plusSeconds(1), NOW));
    }

    @Test
    @DisplayName("Recruiting已过准备时间显示已停转")
    void recruitingReadyTimePast_shouldShowStoppedText() {
        assertEquals("已停转", formatter.format("Recruiting", NOW.minusSeconds(1), NOW));
    }

    @Test
    @DisplayName("Planning未超过24小时显示预计执行时间")
    void planningWithin24Hours_shouldShowPlannedText() {
        LocalDateTime readyTime = LocalDateTime.of(2026, 8, 31, 18, 20, 59);

        assertEquals("预计18:21开始执行", formatter.format("Planning", readyTime, NOW));
    }

    @Test
    @DisplayName("Planning超过24小时显示空转时间")
    void planningOver24Hours_shouldShowIdleText() {
        assertEquals("还需空转24小时00分钟",
                formatter.format("Planning", NOW.plusHours(24).plusSeconds(1), NOW));
    }

    @Test
    @DisplayName("Planning已过准备时间仍显示预计执行时间")
    void planningReadyTimePast_shouldKeepPlannedText() {
        LocalDateTime readyTime = LocalDateTime.of(2026, 8, 31, 11, 59, 59);

        assertEquals("预计12:00开始执行", formatter.format("Planning", readyTime, NOW));
    }

    @Test
    @DisplayName("不支持的OC状态不追加时间文案")
    void unsupportedStatus_shouldReturnEmptyText() {
        assertEquals("", formatter.format("Successful", NOW.plusHours(1), NOW));
    }

    @Test
    @DisplayName("受控查询的文案与时间状态应保持一致")
    void describe_shouldAlignTextWithTimeStatus() {
        OcImageTitleFormatter.Description description =
                formatter.describe("Recruiting", NOW.plusHours(1).plusMinutes(30), NOW);

        assertEquals("1小时30分后停转", description.text());
        assertEquals(OcImageTimeStatusEnum.STOP_COUNTDOWN, description.timeStatus());
    }
}
