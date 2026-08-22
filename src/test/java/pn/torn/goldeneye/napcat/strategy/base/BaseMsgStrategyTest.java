package pn.torn.goldeneye.napcat.strategy.base;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公共用户目标解析测试。
 *
 * <p>验证 at 标记、数字 Torn userId、无参数发送者 QQ 三条路径，以及 at 与数字参数混用、
 * 非法 at 标记的拒绝行为。</p>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseMsgStrategy 公共用户目标解析测试")
class BaseMsgStrategyTest {

    @Mock
    private TornUserManager userManager;

    private TestMsgStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new TestMsgStrategy();
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("at 标记调用 getUserByQq")
    void getTornUser_atTarget_callsGetUserByQq() {
        QqRecMsgSender sender = sender(999L);
        TornUserDO user = new TornUserDO();
        user.setId(1L);
        when(userManager.getUserByQq(12345L)).thenReturn(user);

        TornUserDO result = strategy.getTornUserWithoutException(sender, QqCommandMessage.buildAtMarker(12345L));

        assertEquals(user, result);
        verify(userManager).getUserByQq(12345L);
    }

    @Test
    @DisplayName("数字目标调用 getUserById")
    void getTornUser_numericTarget_callsGetUserById() {
        QqRecMsgSender sender = sender(999L);
        TornUserDO user = new TornUserDO();
        user.setId(12345L);
        when(userManager.getUserById(12345L)).thenReturn(user);

        TornUserDO result = strategy.getTornUserWithoutException(sender, "12345");

        assertEquals(user, result);
        verify(userManager).getUserById(12345L);
    }

    @Test
    @DisplayName("无目标调用 getUserByQq(sender.userId)")
    void getTornUser_noTarget_callsGetUserByQqBySender() {
        QqRecMsgSender sender = sender(888L);
        TornUserDO user = new TornUserDO();
        user.setId(2L);
        when(userManager.getUserByQq(888L)).thenReturn(user);

        TornUserDO result = strategy.getTornUserWithoutException(sender, "");

        assertEquals(user, result);
        verify(userManager).getUserByQq(888L);
    }

    @Test
    @DisplayName("at 与数字参数混用返回参数有误")
    void getTornUser_atAndNumericMixed_rejected() {
        QqRecMsgSender sender = sender(999L);
        String mixedMsg = "12345" + QqCommandMessage.buildAtMarker(67890L);

        BizException exception = assertThrows(BizException.class,
                () -> strategy.getTornUserWithoutException(sender, mixedMsg));

        assertEquals("参数有误", exception.getMsg());
    }

    @Test
    @DisplayName("非法 at 标记返回参数有误")
    void getTornUser_invalidAtMarker_rejected() {
        QqRecMsgSender sender = sender(999L);

        BizException exception = assertThrows(BizException.class,
                () -> strategy.getTornUserWithoutException(sender, QqCommandMessage.INVALID_AT_MARKER));

        assertEquals("参数有误", exception.getMsg());
    }

    @Test
    @DisplayName("at 对应 QQ 未绑定用户时 getTornUser 抛出既有业务提示")
    void getTornUser_atNotBound_throwsBusinessTip() {
        QqRecMsgSender sender = sender(999L);
        String atMarker = QqCommandMessage.buildAtMarker(12345L);
        when(userManager.getUserByQq(12345L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> strategy.getTornUser(sender, atMarker));

        assertEquals("金蝶不认识你哦", exception.getMsg());
        assertNull(exception.getCause());
    }

    private QqRecMsgSender sender(long userId) {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(userId);
        return sender;
    }

    /**
     * 测试用最小策略实现。
     */
    private static class TestMsgStrategy extends BaseMsgStrategy {
        @Override
        public String getCommand() {
            return "test";
        }

        @Override
        public String getCommandDescription() {
            return "test";
        }

        @Override
        public List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
            return List.of();
        }
    }
}
