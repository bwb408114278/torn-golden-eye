package pn.torn.goldeneye.napcat.strategy.faction.attack.commander;

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
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.attack.RwAttackFrequencySummaryVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.service.faction.attack.RwStatWindowService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * RW攻击频率策略测试。
 *
 * <p>验证默认最近确认窗口、显式窗口、空窗口提示和单侧无用户仍输出图片的入口行为。</p>
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW攻击频率策略测试")
class FactionRwAttackPeriodStrategyImplTest {
    private static final long SENDER_QQ = 123456L;
    private static final long FACTION_ID = 999001L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private TornFactionRwDAO rwDao;
    @Mock
    private RwStatWindowService rwStatWindowService;

    private FactionRwAttackPeriodStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new FactionRwAttackPeriodStrategyImpl();
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
        ReflectionTestUtils.setField(strategy, "rwDao", rwDao);
        ReflectionTestUtils.setField(strategy, "rwStatWindowService", rwStatWindowService);
    }

    @Test
    @DisplayName("空参数默认查询最近已确认窗口并返回频率图片")
    void handle_empty_queriesLatestConfirmedWindow() {
        TornFactionRwDO rw = rw(1L);
        RwStatWindowVO window = window("A");
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryLatestConfirmedWindow(rw)).thenReturn(window);
        when(rwStatWindowService.queryFrequency(rw, window)).thenReturn(summary(window, 5, 3));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        verify(rwStatWindowService).queryLatestConfirmedWindow(rw);
        verify(rwStatWindowService).queryFrequency(rw, window);
    }

    @Test
    @DisplayName("显式窗口字母查询指定窗口并返回频率图片")
    void handle_windowCode_queriesExplicitWindow() {
        TornFactionRwDO rw = rw(1L);
        RwStatWindowVO window = window("A");
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryWindow(rw, "A")).thenReturn(window);
        when(rwStatWindowService.queryFrequency(rw, window)).thenReturn(summary(window, 5, 3));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "A");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        verify(rwStatWindowService).queryWindow(rw, "A");
        verify(rwStatWindowService).queryFrequency(rw, window);
    }

    @Test
    @DisplayName("一方无有效出手记录时仍渲染图片而不是返回空提示")
    void handle_oneSideEmpty_rendersImageWithEmptySection() {
        TornFactionRwDO rw = rw(1L);
        RwStatWindowVO window = window("A");
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryLatestConfirmedWindow(rw)).thenReturn(window);
        RwAttackFrequencySummaryVO summary = summary(window, 0, 3);
        summary.setSelfUsers(List.of());
        when(rwStatWindowService.queryFrequency(rw, window)).thenReturn(summary);

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
    }

    @Test
    @DisplayName("默认窗口不存在时返回未查询到已确认对冲窗口")
    void handle_noLatestConfirmed_returnsStableTip() {
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryLatestConfirmedWindow(rw)).thenReturn(null);

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "");

        assertEquals("未查询到已确认对冲窗口", ((TextQqMsg) result.getFirst()).getData().text());
    }

    @Test
    @DisplayName("显式窗口不存在时返回未查询到对冲窗口")
    void handle_explicitWindowMissing_returnsStableTip() {
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryWindow(rw, "A")).thenReturn(null);

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "A");

        assertEquals("未查询到对冲窗口", ((TextQqMsg) result.getFirst()).getData().text());
    }

    @Test
    @DisplayName("all参数查询所有窗口并返回汇总图片")
    void handle_all_queriesAllWindowsCatalog() {
        TornFactionRwDO rw = rw(1L);
        RwStatWindowVO windowA = window("A");
        RwStatWindowVO windowB = window("B");
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryCatalog(rw)).thenReturn(List.of(windowA, windowB));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "all");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        verify(rwStatWindowService).queryCatalog(rw);
        verify(rwStatWindowService, never()).queryLatestConfirmedWindow(any());
        verify(rwStatWindowService, never()).queryFrequency(any(), any());
    }

    @Test
    @DisplayName("all参数没有窗口时返回未查询到对冲窗口")
    void handle_all_noWindow_returnsStableTip() {
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        stubRwQuery(rw);
        when(rwStatWindowService.queryCatalog(rw)).thenReturn(List.of());

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "all");

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
        rw.setOpponentFactionId(999002L);
        rw.setFactionName("己方");
        rw.setOpponentFactionName("对方");
        return rw;
    }

    private RwStatWindowVO window(String code) {
        RwStatWindowVO window = new RwStatWindowVO();
        window.setRwId(1L);
        window.setWindowCode(code);
        window.setStartTime(LocalDateTime.of(2026, 8, 24, 10, 0));
        window.setEndTime(window.getStartTime().plusMinutes(2).plusSeconds(30));
        window.setConfirmed(true);
        return window;
    }

    private RwAttackFrequencySummaryVO summary(RwStatWindowVO window, int selfCount, int opponentCount) {
        RwAttackFrequencySummaryVO summary = new RwAttackFrequencySummaryVO();
        summary.setWindow(window);
        summary.setSelfAttackCount(selfCount);
        summary.setSelfUserCount(selfCount > 0 ? 1 : 0);
        summary.setOpponentAttackCount(opponentCount);
        summary.setOpponentUserCount(opponentCount > 0 ? 1 : 0);
        pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO user = new pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO();
        user.setUserId(100L);
        user.setNickname("测试用户");
        user.setAttackCount(1);
        user.setAttackRatePerMinute(java.math.BigDecimal.ONE);
        summary.setSelfUsers(selfCount > 0 ? List.of(user) : List.of());
        summary.setOpponentUsers(opponentCount > 0 ? List.of(user) : List.of());
        return summary;
    }
}
