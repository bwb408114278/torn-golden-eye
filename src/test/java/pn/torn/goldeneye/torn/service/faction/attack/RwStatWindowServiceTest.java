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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW对冲统计窗口生命周期服务测试。
 *
 * @author Bai
 * @version 1.4.4
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
        when(attackLogDao.queryActiveTimeWindows(eq(1L), eq(2L), eq(3), eq(100), eq(START), eq(rw.getEndTime())))
                .thenReturn(List.of(new AttackTimeWindowDO(START.plusMinutes(20), START.plusMinutes(25))));
        when(windowDao.queryActiveWindows(1L)).thenReturn(List.of(confirmed));
        when(windowDao.insertIgnoreConflict(any(TornFactionRwStatWindowDO.class))).thenReturn(1);

        service.refreshWindows(rw);

        verify(windowDao, never()).updateUnconfirmedWindow(any(), eq(true));
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

        service.refreshWindows(rw);

        verify(windowDao).updateUnconfirmedWindow(any(TornFactionRwStatWindowDO.class), eq(false));
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
}
