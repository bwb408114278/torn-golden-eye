package pn.torn.goldeneye.configuration.socket.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.configuration.socket.service.BotReplyService;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BasePrivateMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.manage.PrivateDocStrategyImpl;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 私聊消息处理器 at 目标分流测试。
 *
 * <p>验证私聊与群聊在 at 传递/拒绝上的语义一致：支持 at 的策略收到合法内部 at 标记并
 * 进入公共用户查询入口，不支持 at 的策略收到标记时回复稳定错误且不执行策略。</p>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.22
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrivateMessageHandler at 目标分流测试")
class PrivateMessageHandlerTest {

    private static final long SENDER_QQ = 999L;
    private static final long AT_TARGET_QQ = 12345L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private PrivateDocStrategyImpl privateDocStrategy;
    @Mock
    private BotReplyService botReplyService;

    private final StubPrivateUserTargetStrategy atSupportStrategy =
            new StubPrivateUserTargetStrategy("战力增长", true);
    private final StubPrivateUserTargetStrategy plainStrategy =
            new StubPrivateUserTargetStrategy("OC分配", false);

    private PrivateMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PrivateMessageHandler(List.of(atSupportStrategy, plainStrategy),
                privateDocStrategy, botReplyService);
        ReflectionTestUtils.setField(atSupportStrategy, "userManager", userManager);
        ReflectionTestUtils.setField(plainStrategy, "userManager", userManager);
    }

    @Test
    @DisplayName("支持 at 的策略收到合法标记并按目标 QQ 进入公共用户查询")
    void handle_atSupportStrategy_passesMarkerAndQueriesTargetQq() {
        String atMarker = QqCommandMessage.buildAtMarker(AT_TARGET_QQ);

        handler.handle(msg(), commandArray("g#战力增长#"), atMarker);

        verify(userManager).getUserByQq(AT_TARGET_QQ);
        assertTrue(atSupportStrategy.isHandleInvoked());
        assertEquals(atMarker, atSupportStrategy.getLastParam());
        verify(botReplyService).replyPrivate(any(BotSocketReqParam.class));
    }

    @Test
    @DisplayName("不支持 at 的策略收到标记时回复稳定错误且不执行策略")
    void handle_atUnsupportedStrategy_repliesErrorWithoutHandle() {
        String atMarker = QqCommandMessage.buildAtMarker(AT_TARGET_QQ);

        handler.handle(msg(), commandArray("g#OC分配#"), atMarker);

        assertFalse(plainStrategy.isHandleInvoked());
        verify(userManager, never()).getUserByQq(anyLong());
        ArgumentCaptor<BotSocketReqParam> replyCaptor = ArgumentCaptor.forClass(BotSocketReqParam.class);
        verify(botReplyService).replyPrivate(replyCaptor.capture());
        assertTrue(replyCaptor.getValue().getParams().toString().contains("该指令不支持@用户查询"),
                "私聊拒绝回复应包含与群聊一致的稳定错误文案");
    }

    @Test
    @DisplayName("无 at 标记时仍按原始纯文本参数执行策略")
    void handle_noMarker_keepsPlainParam() {
        handler.handle(msg(), commandArray("g#战力增长#12345"), "");

        assertTrue(atSupportStrategy.isHandleInvoked());
        assertEquals("12345", atSupportStrategy.getLastParam());
        verify(userManager).getUserById(12345L);
    }

    private QqRecMsg msg() {
        QqRecMsg msg = new QqRecMsg();
        msg.setUserId(SENDER_QQ);
        msg.setSender(new QqRecMsgSender());
        return msg;
    }

    private String[] commandArray(String commandText) {
        return commandText.split("#", 3);
    }

    /**
     * 测试用私聊用户目标策略桩：记录参数并调用公共用户解析入口。
     */
    private static class StubPrivateUserTargetStrategy extends BasePrivateMsgStrategy {
        private final String command;
        private final boolean supportsAt;
        private boolean handleInvoked;
        private String lastParam;

        private StubPrivateUserTargetStrategy(String command, boolean supportsAt) {
            this.command = command;
            this.supportsAt = supportsAt;
        }

        @Override
        public String getCommand() {
            return command;
        }

        @Override
        public String getCommandDescription() {
            return "test";
        }

        @Override
        public boolean supportsAtUserTarget() {
            return supportsAt;
        }

        @Override
        public List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
            handleInvoked = true;
            lastParam = msg;
            getTornUserWithoutException(sender, msg);
            return List.of(new TextQqMsg("ok"));
        }

        private boolean isHandleInvoked() {
            return handleInvoked;
        }

        private String getLastParam() {
            return lastParam;
        }
    }
}
