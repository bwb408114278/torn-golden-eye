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
import pn.torn.goldeneye.configuration.socket.service.GroupPermissionService;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgReqParam;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.manage.DocStrategyImpl;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 群聊消息处理器 at 目标分流测试。
 *
 * <p>验证支持 at 的策略收到合法内部 at 标记并进入公共用户查询入口、不支持 at 的策略
 * 收到标记时回复稳定错误且不执行策略，以及无标记时保持纯文本参数行为。</p>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.22
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GroupMessageHandler at 目标分流测试")
class GroupMessageHandlerTest {

    private static final long GROUP_ID = 111L;
    private static final long SENDER_QQ = 999L;
    private static final long AT_TARGET_QQ = 12345L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private DocStrategyImpl docStrategy;
    @Mock
    private TornSettingFactionManager factionManager;
    @Mock
    private GroupPermissionService groupPermissionService;
    @Mock
    private BotReplyService botReplyService;

    private final StubUserTargetStrategy atSupportStrategy = new StubUserTargetStrategy("战力增长", true);
    private final StubUserTargetStrategy plainStrategy = new StubUserTargetStrategy("OC分配", false);

    private GroupMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GroupMessageHandler(List.of(atSupportStrategy, plainStrategy), docStrategy,
                factionManager, groupPermissionService, botReplyService);
        ReflectionTestUtils.setField(atSupportStrategy, "userManager", userManager);
        ReflectionTestUtils.setField(plainStrategy, "userManager", userManager);
        when(groupPermissionService.invalidAdmin(anyLong(), any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("支持 at 的策略收到合法标记并按目标 QQ 进入公共用户查询")
    void handle_atSupportStrategy_passesMarkerAndQueriesTargetQq() {
        String atMarker = QqCommandMessage.buildAtMarker(AT_TARGET_QQ);

        handler.handle(msg(), commandArray("g#战力增长#"), atMarker, new TornFactionBO(new TornSettingFactionDO()));

        verify(userManager).getUserByQq(AT_TARGET_QQ);
        assertTrue(atSupportStrategy.isHandleInvoked());
        assertEquals(atMarker, atSupportStrategy.getLastParam());
        verify(botReplyService).replyGroup(any(), any(BotSocketReqParam.class));
    }

    @Test
    @DisplayName("不支持 at 的策略收到标记时回复稳定错误且不执行策略")
    void handle_atUnsupportedStrategy_repliesErrorWithoutHandle() {
        String atMarker = QqCommandMessage.buildAtMarker(AT_TARGET_QQ);

        handler.handle(msg(), commandArray("g#OC分配#"), atMarker, new TornFactionBO(new TornSettingFactionDO()));

        assertFalse(plainStrategy.isHandleInvoked());
        verify(userManager, never()).getUserByQq(anyLong());
        ArgumentCaptor<BotSocketReqParam> replyCaptor = ArgumentCaptor.forClass(BotSocketReqParam.class);
        verify(botReplyService).replyGroup(any(), replyCaptor.capture());
        assertEquals(List.of(new TextQqMsg("该指令不支持@用户查询")),
                ((GroupMsgReqParam) replyCaptor.getValue().getParams()).getMessage());
    }

    @Test
    @DisplayName("无 at 标记时仍按原始纯文本参数执行策略")
    void handle_noMarker_keepsPlainParam() {
        handler.handle(msg(), commandArray("g#战力增长#12345"), "", new TornFactionBO(new TornSettingFactionDO()));

        assertTrue(atSupportStrategy.isHandleInvoked());
        assertEquals("12345", atSupportStrategy.getLastParam());
        verify(userManager).getUserById(12345L);
    }

    @Test
    @DisplayName("多 at 的非法标记由公共用户解析返回参数有误")
    void handle_multipleAtMarker_rejectedByUserResolution() {
        handler.handle(msg(), commandArray("g#战力增长#"), QqCommandMessage.INVALID_AT_MARKER, new TornFactionBO(new TornSettingFactionDO()));

        verify(userManager, never()).getUserByQq(anyLong());
        ArgumentCaptor<BotSocketReqParam> replyCaptor = ArgumentCaptor.forClass(BotSocketReqParam.class);
        verify(botReplyService).replyGroup(any(), replyCaptor.capture());
        assertEquals(List.of(new TextQqMsg("参数有误")),
                ((GroupMsgReqParam) replyCaptor.getValue().getParams()).getMessage());
    }

    private QqRecMsg msg() {
        QqRecMsg msg = new QqRecMsg();
        msg.setGroupId(GROUP_ID);
        msg.setUserId(SENDER_QQ);
        msg.setSender(new QqRecMsgSender());
        return msg;
    }

    private String[] commandArray(String commandText) {
        return commandText.split("#", 3);
    }

    /**
     * 测试用用户目标策略桩：记录参数并调用公共用户解析入口。
     */
    private static class StubUserTargetStrategy extends BaseGroupMsgStrategy {
        private final String command;
        private final boolean supportsAt;
        private boolean handleInvoked;
        private String lastParam;

        private StubUserTargetStrategy(String command, boolean supportsAt) {
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
        public TornFactionRoleTypeEnum getRoleType() {
            return null;
        }

        @Override
        public boolean supportsAtUserTarget() {
            return supportsAt;
        }

        @Override
        public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
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
