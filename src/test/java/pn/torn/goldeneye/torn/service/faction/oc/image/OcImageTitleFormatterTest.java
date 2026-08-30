package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageTimeStatusEnum;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC 表格图片标题时间解析测试。
 * <p>
 * 验证 readyTime 为 null、停转倒计时、24小时边界、空转、已停转、预计执行和非目标状态互斥输出。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@DisplayName("OC标题时间解析测试")
class OcImageTitleFormatterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
    private final OcImageTitleFormatter formatter = new OcImageTitleFormatter();

    @Test
    @DisplayName("readyTime为null时不显示时间文案")
    void readyTimeNull_shouldReturnNoText() {
        assertEquals("", formatter.format("Recruiting", null, NOW));
        assertEquals(OcImageTimeStatusEnum.NONE,
                formatter.resolve("Recruiting", null, NOW));
    }

    @Test
    @DisplayName("Recruiting剩余不足24小时显示停转倒计时")
    void recruitingDeltaLessThan24h_shouldShowStopCountdown() {
        LocalDateTime readyTime = NOW.plusHours(2).plusMinutes(30);
        assertEquals("2小时30分后停转", formatter.format("Recruiting", readyTime, NOW));
    }

    @Test
    @DisplayName("Recruiting剩余恰好24小时归入停转倒计时")
    void recruitingDeltaExactly24h_shouldShowStopCountdown() {
        LocalDateTime readyTime = NOW.plusHours(24);
        assertEquals("24小时00分后停转", formatter.format("Recruiting", readyTime, NOW));
    }

    @Test
    @DisplayName("Recruiting超过24小时显示还需空转")
    void recruitingDeltaOver24h_shouldShowIdle() {
        LocalDateTime readyTime = NOW.plusHours(26).plusMinutes(5);
        assertEquals("还需空转26小时05分钟", formatter.format("Recruiting", readyTime, NOW));
    }

    @Test
    @DisplayName("Planning超过24小时显示还需空转")
    void planningDeltaOver24h_shouldShowIdle() {
        LocalDateTime readyTime = NOW.plusDays(3).plusHours(2);
        assertEquals("还需空转74小时00分钟", formatter.format("Planning", readyTime, NOW));
    }

    @Test
    @DisplayName("Recruiting已过readyTime优先显示已停转")
    void recruitingReadyTimePast_shouldShowStopped() {
        LocalDateTime readyTime = NOW.minusMinutes(1);
        assertEquals("已停转", formatter.format("Recruiting", readyTime, NOW));
        assertEquals(OcImageTimeStatusEnum.STOPPED,
                formatter.resolve("Recruiting", readyTime, NOW));
    }

    @Test
    @DisplayName("Planning剩余不足24小时按分钟截断加1分钟显示预计执行")
    void planningDeltaLessThan24h_shouldShowPlannedExecution() {
        LocalDateTime readyTime = LocalDateTime.of(2026, 9, 1, 12, 44, 30);
        assertEquals("预计12:45开始执行", formatter.format("Planning", readyTime, NOW));
    }

    @Test
    @DisplayName("Planning已过readyTime仍显示预计执行，不得返回空文案")
    void planningReadyTimePast_shouldShowPlannedExecution() {
        LocalDateTime readyTime = NOW.minusMinutes(5);
        assertEquals("预计11:56开始执行", formatter.format("Planning", readyTime, NOW));
        assertEquals(OcImageTimeStatusEnum.PLANNED,
                formatter.resolve("Planning", readyTime, NOW));
    }

    @Test
    @DisplayName("剩余24小时00分01秒按完整时间精度属于空转")
    void delta24hPlusOneSecond_shouldShowIdle() {
        LocalDateTime readyTime = NOW.plusHours(24).plusSeconds(1);
        assertEquals("还需空转24小时00分钟", formatter.format("Recruiting", readyTime, NOW));
        assertEquals("还需空转24小时00分钟", formatter.format("Planning", readyTime, NOW));
        assertEquals(OcImageTimeStatusEnum.IDLE, formatter.resolve("Recruiting", readyTime, NOW));
        assertEquals(OcImageTimeStatusEnum.IDLE, formatter.resolve("Planning", readyTime, NOW));
    }

    @Test
    @DisplayName("Planning剩余恰好24小时仍显示预计执行而非空转")
    void planningDeltaExactly24h_shouldShowPlanned() {
        LocalDateTime readyTime = NOW.plusHours(24);
        assertEquals("预计12:01开始执行", formatter.format("Planning", readyTime, NOW));
    }

    @Test
    @DisplayName("非目标状态不误显示时间文案")
    void unsupportedStatus_shouldReturnNoText() {
        assertEquals("", formatter.format("Successful", NOW.plusHours(1), NOW));
        assertEquals(OcImageTimeStatusEnum.NONE,
                formatter.resolve("Successful", NOW.plusHours(1), NOW));
    }
}
