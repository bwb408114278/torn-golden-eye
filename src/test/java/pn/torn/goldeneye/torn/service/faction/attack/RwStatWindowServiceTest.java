package pn.torn.goldeneye.torn.service.faction.attack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwStatWindowDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwStatWindowDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwAttackFrequencySummaryVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RW对冲统计窗口生命周期服务测试。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW对冲统计窗口生命周期服务测试")
class RwStatWindowServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 24, 10, 0);

    @Mock
    private TornFactionRwStatWindowDAO windowDao;

    @Mock
    private TornAttackLogDAO attackLogDao;

    private RwStatWindowService service;

    @BeforeEach
    void setUp() {
        service = new RwStatWindowService(windowDao, attackLogDao);
    }

    @Test
    @DisplayName("已确认窗口不改写并为新窗口追加下一个字母")
    void refreshWindows_confirmedWindowRemainsStable_andAppendsNextCode() {
        TornFactionRwDO rw = rw(1L, LocalDateTime.of(2026, 8, 24, 13, 0));
        TornFactionRwStatWindowDO confirmed = window(1L, "A", START, START.plusMinutes(10), true);
        when(attackLogDao.queryActiveTimeWindows(1L, 2L, 3, 100, START, rw.getEndTime()))
                .thenReturn(List.of(new AttackTimeWindowDO(START.plusMinutes(20), START.plusMinutes(25))));
        when(windowDao.queryActiveWindows(1L)).thenReturn(List.of(confirmed));
        when(windowDao.insertIgnoreConflict(any(TornFactionRwStatWindowDO.class))).thenReturn(1);

        service.refreshWindows(rw);

        verify(windowDao, never()).updateUnconfirmedWindow(any(), any(boolean.class));
        verify(windowDao).insertIgnoreConflict(org.mockito.ArgumentMatchers.argThat(window ->
                "B".equals(window.getWindowCode()) && Boolean.TRUE.equals(window.getConfirmed())));
    }

    @Test
    @DisplayName("进行中窗口可以更新但保持未确认状态")
    void refreshWindows_unconfirmedWindowUpdatesWithoutConfirmation() {
        TornFactionRwDO rw = rw(1L, null);
        LocalDateTime observedAt = LocalDateTime.now();
        AttackTimeWindowDO candidate = new AttackTimeWindowDO(observedAt.minusMinutes(2), observedAt.minusSeconds(10));
        TornFactionRwStatWindowDO unconfirmed = window(2L, "A", candidate.start(), candidate.end(), false);
        when(attackLogDao.queryActiveTimeWindows(eq(1L), eq(2L), eq(3), eq(100), eq(START), any(LocalDateTime.class)))
                .thenReturn(List.of(candidate));
        when(windowDao.queryActiveWindows(1L)).thenReturn(List.of(unconfirmed));
        when(windowDao.updateUnconfirmedWindow(any(TornFactionRwStatWindowDO.class), eq(false))).thenReturn(1);

        service.refreshWindows(rw);

        verify(windowDao).updateUnconfirmedWindow(any(TornFactionRwStatWindowDO.class), eq(false));
    }

    @Test
    @DisplayName("插入冲突后重新读取窗口列表并继续追加正确窗口编码")
    void refreshWindows_insertConflict_reloadsAndAppendsNextCode() {
        TornFactionRwDO rw = rw(1L, LocalDateTime.of(2026, 8, 24, 13, 0));
        AttackTimeWindowDO candidate = new AttackTimeWindowDO(START.plusMinutes(20), START.plusMinutes(25));
        TornFactionRwStatWindowDO concurrentA = window(99L, "A", START, START.plusMinutes(10), true);
        when(attackLogDao.queryActiveTimeWindows(1L, 2L, 3, 100, START, rw.getEndTime()))
                .thenReturn(List.of(candidate));
        when(windowDao.queryActiveWindows(1L))
                .thenReturn(List.of())
                .thenReturn(List.of(concurrentA));
        when(windowDao.insertIgnoreConflict(any(TornFactionRwStatWindowDO.class)))
                .thenReturn(0)
                .thenReturn(1);

        service.refreshWindows(rw);

        verify(windowDao, times(2)).queryActiveWindows(1L);
        verify(windowDao, times(2)).insertIgnoreConflict(any(TornFactionRwStatWindowDO.class));
        verify(windowDao).insertIgnoreConflict(org.mockito.ArgumentMatchers.argThat(window ->
                "A".equals(window.getWindowCode()) && Boolean.TRUE.equals(window.getConfirmed())));
        verify(windowDao).insertIgnoreConflict(org.mockito.ArgumentMatchers.argThat(window ->
                "B".equals(window.getWindowCode()) && Boolean.TRUE.equals(window.getConfirmed())));
    }

    @Test
    @DisplayName("未确认窗口更新行数为0时重新读取并按已确认窗口跳过")
    void refreshWindows_updateConflict_reloadsAndSkipsConfirmedOverlap() {
        TornFactionRwDO rw = rw(1L, LocalDateTime.of(2026, 8, 24, 13, 0));
        AttackTimeWindowDO candidate = new AttackTimeWindowDO(START, START.plusMinutes(10));
        TornFactionRwStatWindowDO unconfirmed = window(2L, "A", candidate.start(), candidate.end(), false);
        TornFactionRwStatWindowDO confirmed = window(2L, "A", candidate.start(), candidate.end(), true);
        when(attackLogDao.queryActiveTimeWindows(1L, 2L, 3, 100, START, rw.getEndTime()))
                .thenReturn(List.of(candidate));
        when(windowDao.queryActiveWindows(1L))
                .thenReturn(List.of(unconfirmed))
                .thenReturn(List.of(confirmed));
        when(windowDao.updateUnconfirmedWindow(any(TornFactionRwStatWindowDO.class), eq(true)))
                .thenReturn(0);

        service.refreshWindows(rw);

        verify(windowDao, times(2)).queryActiveWindows(1L);
        verify(windowDao, times(1)).updateUnconfirmedWindow(any(TornFactionRwStatWindowDO.class), eq(true));
        verify(windowDao, never()).insertIgnoreConflict(any(TornFactionRwStatWindowDO.class));
    }

    @Test
    @DisplayName("全部窗口合并按人统计并以窗口总秒数计算频率")
    void queryFrequencyForAllWindows_mergesWindowsAndCalculatesRate() {
        TornFactionRwDO rw = rw(1L, null);
        RwStatWindowVO windowA = statWindow("A", START, START.plusMinutes(2).plusSeconds(30));
        RwStatWindowVO windowB = statWindow("B", START.plusMinutes(10), START.plusMinutes(10).plusSeconds(30));
        RwUserAttackStatVO selfUser = user(101L, 1L, 4);
        RwUserAttackStatVO opponentUser = user(201L, 2L, 1);
        when(windowDao.queryUserAttackStatsByRw(1L, 1L, 2L)).thenReturn(List.of(selfUser, opponentUser));

        RwAttackFrequencySummaryVO summary = service.queryFrequencyForAllWindows(rw, List.of(windowA, windowB));

        assertNull(summary.getWindow());
        assertEquals(2, summary.getWindowCount());
        assertEquals(180, summary.getTotalWindowSeconds());
        assertEquals(4, summary.getSelfAttackCount());
        assertEquals(1, summary.getSelfUserCount());
        assertEquals(1, summary.getOpponentAttackCount());
        assertEquals(1, summary.getOpponentUserCount());
        assertEquals(0, new BigDecimal("1.33").compareTo(selfUser.getAttackRatePerMinute()));
        assertEquals(0, new BigDecimal("0.33").compareTo(opponentUser.getAttackRatePerMinute()));
    }

    private TornFactionRwDO rw(Long id, LocalDateTime endTime) {
        TornFactionRwDO rw = new TornFactionRwDO();
        rw.setId(id);
        rw.setFactionId(1L);
        rw.setOpponentFactionId(2L);
        rw.setStartTime(START);
        rw.setEndTime(endTime);
        return rw;
    }

    private TornFactionRwStatWindowDO window(Long id, String code, LocalDateTime start,
                                             LocalDateTime end, boolean confirmed) {
        TornFactionRwStatWindowDO window = new TornFactionRwStatWindowDO();
        window.setId(id);
        window.setRwId(1L);
        window.setWindowCode(code);
        window.setStartTime(start);
        window.setEndTime(end);
        window.setConfirmed(confirmed);
        return window;
    }

    private RwStatWindowVO statWindow(String code, LocalDateTime start, LocalDateTime end) {
        RwStatWindowVO window = new RwStatWindowVO();
        window.setRwId(1L);
        window.setWindowCode(code);
        window.setStartTime(start);
        window.setEndTime(end);
        window.setConfirmed(true);
        return window;
    }

    private RwUserAttackStatVO user(long userId, long attackFactionId, int attackCount) {
        RwUserAttackStatVO user = new RwUserAttackStatVO();
        user.setUserId(userId);
        user.setAttackFactionId(attackFactionId);
        user.setNickname("用户" + userId);
        user.setAttackCount(attackCount);
        return user;
    }
}
