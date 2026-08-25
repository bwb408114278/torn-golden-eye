package pn.torn.goldeneye.napcat.strategy.faction.attack.publish;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.service.faction.attack.RwStatWindowService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RW战神策略测试。
 *
 * <p>验证无窗口保持全场活跃窗口路径、纯数字RWID兼容和显式窗口单窗口路径。</p>
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW战神策略测试")
class FactionRwAttackStrategyImplTest {
    private static final long SENDER_QQ = 123456L;
    private static final long FACTION_ID = 999001L;
    private static final long OPPONENT_FACTION_ID = 999002L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private TornFactionRwDAO rwDao;
    @Mock
    private TornAttackLogDAO attackLogDao;
    @Mock
    private RwStatWindowService rwStatWindowService;

    private FactionRwAttackStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new FactionRwAttackStrategyImpl();
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
        ReflectionTestUtils.setField(strategy, "rwDao", rwDao);
        ReflectionTestUtils.setField(strategy, "attackLogDao", attackLogDao);
        ReflectionTestUtils.setField(strategy, "rwStatWindowService", rwStatWindowService);
    }

    @Test
    @DisplayName("空参数保持全场活跃窗口战神统计")
    void handle_empty_keepsFullRwActiveWindowStats() {
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(attackLogDao.queryActiveTimeWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), eq(3), eq(100),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(new AttackTimeWindowDO(LocalDateTime.of(2026, 8, 24, 10, 0),
                        LocalDateTime.of(2026, 8, 24, 10, 3))));
        when(attackLogDao.queryPlayerAttackStatByWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), anyList()))
                .thenReturn(List.of(playerStat()));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        verify(attackLogDao).queryActiveTimeWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), eq(3), eq(100),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("纯数字参数仍按RWID解析并保持全场统计")
    void handle_numericRwId_keepsFullRwStats() {
        TornFactionRwDO rw = rw(123L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(attackLogDao.queryActiveTimeWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), eq(3), eq(100),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(new AttackTimeWindowDO(LocalDateTime.of(2026, 8, 24, 10, 0),
                        LocalDateTime.of(2026, 8, 24, 10, 3))));
        when(attackLogDao.queryPlayerAttackStatByWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), anyList()))
                .thenReturn(List.of(playerStat()));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "123");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        verify(attackLogDao).queryActiveTimeWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), eq(3), eq(100),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(rwStatWindowService, org.mockito.Mockito.never()).queryWindow(any(), any());
    }

    @Test
    @DisplayName("显式窗口字母只传单一窗口给战神统计")
    void handle_windowCode_passesSingleWindow() {
        TornFactionRwDO rw = rw(1L);
        RwStatWindowVO window = window("A");
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryWindow(rw, "A")).thenReturn(window);
        when(attackLogDao.queryPlayerAttackStatByWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), anyList()))
                .thenReturn(List.of(playerStat()));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "A");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        verify(attackLogDao).queryPlayerAttackStatByWindows(eq(FACTION_ID), eq(OPPONENT_FACTION_ID), anyList());
        verify(attackLogDao, org.mockito.Mockito.never()).queryActiveTimeWindows(anyLong(), anyLong(),
                anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("指定窗口不存在时返回未查询到对冲窗口")
    void handle_windowMissing_returnsStableTip() {
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryWindow(rw, "A")).thenReturn(null);

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "A");

        assertEquals("未查询到对冲窗口", ((TextQqMsg) result.getFirst()).getData().text());
    }

    private void stubRwQuery(TornFactionRwDO rw) {
        LambdaQueryChainWrapper<TornFactionRwDO> query = mock(LambdaQueryChainWrapper.class);
        when(rwDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.eq(anyBoolean(), any(), any())).thenReturn(query);
        when(query.le(anyBoolean(), any(), any())).thenReturn(query);
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        Page<TornFactionRwDO> page = new Page<>(1, 1);
        page.setRecords(List.of(rw));
        when(query.page(any(Page.class))).thenReturn(page);
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(SENDER_QQ);
        return sender;
    }

    private TornUserDO user() {
        TornUserDO user = new TornUserDO();
        user.setId(100001L);
        user.setFactionId(FACTION_ID);
        return user;
    }

    private TornFactionRwDO rw(long id) {
        TornFactionRwDO rw = new TornFactionRwDO();
        rw.setId(id);
        rw.setFactionId(FACTION_ID);
        rw.setOpponentFactionId(OPPONENT_FACTION_ID);
        rw.setFactionName("己方");
        rw.setOpponentFactionName("对方");
        rw.setStartTime(LocalDateTime.of(2026, 8, 24, 10, 0));
        rw.setEndTime(LocalDateTime.of(2026, 8, 24, 11, 0));
        return rw;
    }

    private RwStatWindowVO window(String code) {
        RwStatWindowVO window = new RwStatWindowVO();
        window.setRwId(1L);
        window.setWindowCode(code);
        window.setStartTime(LocalDateTime.of(2026, 8, 24, 10, 0));
        window.setEndTime(LocalDateTime.of(2026, 8, 24, 10, 2).plusSeconds(30));
        window.setConfirmed(true);
        return window;
    }

    private PlayerAttackStatDO playerStat() {
        PlayerAttackStatDO stat = new PlayerAttackStatDO();
        stat.setUserId(1L);
        stat.setNickname("测试");
        stat.setTotalAttacks(1);
        stat.setHospCount(0);
        stat.setLeaveCount(0);
        stat.setAssistCount(0);
        stat.setLostCount(0);
        stat.setTotalRounds(1);
        stat.setDamageDealt(100L);
        stat.setDamageTaken(10L);
        stat.setDamageScore(BigDecimal.ONE);
        stat.setSyringeUsed(0);
        stat.setSpecialAmmoRounds(0);
        stat.setDebuffTempCount(0);
        stat.setTotalCombatDuration(10L);
        stat.setAvgCombatDuration(BigDecimal.TEN);
        stat.setOnlineOpponentCount(1);
        stat.setAvgOpponentElo(BigDecimal.valueOf(1000));
        return stat;
    }
}
