package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwRankDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RW战神榜名次结算服务测试。
 *
 * <p>只验证调用链与物理删除先于插入的顺序，不重复SQL边界与名次口径断言。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW战神榜名次结算服务测试")
class RwRankSettleServiceTest {
    private static final long RW_ID = 48522L;
    private static final long FACTION_ID = 20465L;
    private static final long OPPONENT_FACTION_ID = 231L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 4, 21, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 6, 14, 40, 23);

    @Mock
    private TornFactionRwRankDAO rankDao;
    @Mock
    private TornAttackLogDAO attackLogDao;

    private RwRankSettleService settleService;

    @BeforeEach
    void setUp() {
        settleService = new RwRankSettleService(rankDao, attackLogDao);
    }

    @Test
    @DisplayName("结算先物理删除旧行再按统计顺序写入名次")
    @SuppressWarnings("unchecked")
    void settle_physicalDeleteBeforeInsert_andRanksByStatOrder() {
        when(attackLogDao.queryActiveTimeWindows(FACTION_ID, OPPONENT_FACTION_ID, 3, 100, START, END))
                .thenReturn(List.of(new AttackTimeWindowDO(START, END)));
        when(attackLogDao.queryPlayerAttackStatByWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), anyList()))
                .thenReturn(List.of(stat(11L, "Cinderine", "2709709.88"), stat(22L, "Ciallo", "1829652.03")));
        ArgumentCaptor<List<TornFactionRwRankDO>> captor = ArgumentCaptor.forClass(List.class);

        settleService.settle(rw());

        InOrder inOrder = inOrder(rankDao, attackLogDao);
        inOrder.verify(rankDao).physicalDeleteByRwId(RW_ID);
        inOrder.verify(attackLogDao).queryActiveTimeWindows(FACTION_ID, OPPONENT_FACTION_ID, 3, 100, START, END);
        inOrder.verify(attackLogDao).queryPlayerAttackStatByWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), anyList());
        inOrder.verify(rankDao).saveBatch(captor.capture());

        List<TornFactionRwRankDO> savedList = captor.getValue();
        assertEquals(2, savedList.size());
        assertEquals(11L, savedList.getFirst().getUserId());
        assertEquals(1, savedList.getFirst().getRankNum());
        assertEquals("Cinderine", savedList.getFirst().getNickname());
        assertEquals(0, new BigDecimal("2709709.88").compareTo(savedList.getFirst().getDamageScore()));
        assertEquals(22L, savedList.get(1).getUserId());
        assertEquals(2, savedList.get(1).getRankNum());
    }

    @Test
    @DisplayName("无活跃窗口时只清空旧行不写入任何结算行")
    void settle_noActiveWindow_skipsInsert() {
        when(attackLogDao.queryActiveTimeWindows(FACTION_ID, OPPONENT_FACTION_ID, 3, 100, START, END))
                .thenReturn(List.of());

        settleService.settle(rw());

        verify(rankDao).physicalDeleteByRwId(RW_ID);
        verify(rankDao, never()).saveBatch(anyList());
        verify(attackLogDao, never()).queryPlayerAttackStatByWindows(anyLong(), anyLong(), anyList());
    }

    private TornFactionRwDO rw() {
        TornFactionRwDO rw = new TornFactionRwDO();
        rw.setId(RW_ID);
        rw.setFactionId(FACTION_ID);
        rw.setOpponentFactionId(OPPONENT_FACTION_ID);
        rw.setStartTime(START);
        rw.setEndTime(END);
        return rw;
    }

    private PlayerAttackStatDO stat(long userId, String nickname, String damageScore) {
        PlayerAttackStatDO stat = new PlayerAttackStatDO();
        stat.setUserId(userId);
        stat.setNickname(nickname);
        stat.setDamageScore(new BigDecimal(damageScore));
        return stat;
    }
}
