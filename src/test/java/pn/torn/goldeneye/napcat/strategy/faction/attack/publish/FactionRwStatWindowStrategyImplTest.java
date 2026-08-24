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
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.service.faction.attack.RwStatWindowService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * RW对冲窗口目录策略测试。
 *
 * <p>验证当前RW、指定RWID、非法窗口参数和无窗口提示的入口行为。</p>
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW对冲窗口目录策略测试")
class FactionRwStatWindowStrategyImplTest {
    private static final long SENDER_QQ = 123456L;
    private static final long FACTION_ID = 999001L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private TornFactionRwDAO rwDao;
    @Mock
    private RwStatWindowService rwStatWindowService;

    private FactionRwStatWindowStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new FactionRwStatWindowStrategyImpl();
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
        ReflectionTestUtils.setField(strategy, "rwDao", rwDao);
        ReflectionTestUtils.setField(strategy, "rwStatWindowService", rwStatWindowService);
    }

    @Test
    @DisplayName("空参数查询当前RW窗口目录并返回图片")
    void handle_empty_returnsCatalogImage() {
        TornUserDO user = user(FACTION_ID);
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user);
        stubRwQuery(rw);
        when(rwStatWindowService.queryCatalog(rw)).thenReturn(List.of(window("A")));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        verify(rwStatWindowService).queryCatalog(rw);
    }

    @Test
    @DisplayName("纯数字参数按RWID查询窗口目录并返回图片")
    void handle_rwId_returnsCatalogImage() {
        TornUserDO user = user(FACTION_ID);
        TornFactionRwDO rw = rw(123L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user);
        stubRwQuery(rw);
        when(rwStatWindowService.queryCatalog(rw)).thenReturn(List.of(window("A")));

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "123");

        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        assertEquals(123L, rw.getId());
    }

    @Test
    @DisplayName("窗口字母参数在目录指令中返回参数有误")
    void handle_windowCode_rejected() {
        TornUserDO user = user(FACTION_ID);
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user);
        stubRwQuery(rw);

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "A");

        assertEquals("参数有误", ((TextQqMsg) result.getFirst()).getData().text());
    }

    @Test
    @DisplayName("没有窗口时返回未查询到对冲窗口")
    void handle_noWindow_returnsStableTip() {
        TornUserDO user = user(FACTION_ID);
        TornFactionRwDO rw = rw(1L);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user);
        stubRwQuery(rw);
        when(rwStatWindowService.queryCatalog(rw)).thenReturn(List.of());

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "");

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

    private TornUserDO user(long factionId) {
        TornUserDO user = new TornUserDO();
        user.setId(100001L);
        user.setFactionId(factionId);
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
        window.setStartTime(java.time.LocalDateTime.of(2026, 8, 24, 10, 0));
        window.setEndTime(window.getStartTime().plusMinutes(3));
        window.setConfirmed(true);
        return window;
    }
}
