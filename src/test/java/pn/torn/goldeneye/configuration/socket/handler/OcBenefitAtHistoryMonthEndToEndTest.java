package pn.torn.goldeneye.configuration.socket.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.socket.dispatch.BotMessageDispatcher;
import pn.torn.goldeneye.configuration.socket.service.BlockedWordService;
import pn.torn.goldeneye.configuration.socket.service.BotReplyService;
import pn.torn.goldeneye.configuration.socket.service.GroupPermissionService;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.faction.crime.benefit.BaseOcBenefitQueryStrategy;
import pn.torn.goldeneye.napcat.strategy.manage.DocStrategyImpl;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * OC收益指令 at+历史月端到端测试。
 *
 * <p>用真实原始JSON消息走完整分发链：JSON反序列化、入站解析器、调度器切分（含QQ at卡片
 * 自动空格容忍）、群消息处理器参数组装（at标记追加在参数末尾）与真实
 * {@link BaseOcBenefitQueryStrategy}解析，验证策略最终收到at目标与目标月份，
 * 覆盖at卡片后带自动空格与at在消息末尾两种真实客户端形态。</p>
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC收益指令 at+历史月端到端测试")
class OcBenefitAtHistoryMonthEndToEndTest {
    private static final long GROUP_ID = 111L;
    private static final long SENDER_QQ = 999L;
    private static final long AT_TARGET_QQ = 12345L;

    @Mock
    private TornSettingFactionManager factionManager;
    @Mock
    private BlockedWordService blockedWordService;
    @Mock
    private PrivateMessageHandler privateMessageHandler;
    @Mock
    private DocStrategyImpl docStrategy;
    @Mock
    private GroupPermissionService groupPermissionService;
    @Mock
    private BotReplyService botReplyService;

    private final StubOcBenefitStrategy strategy = new StubOcBenefitStrategy();
    private BotMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        GroupMessageHandler groupHandler = new GroupMessageHandler(List.of(strategy), docStrategy,
                factionManager, groupPermissionService, botReplyService);
        dispatcher = new BotMessageDispatcher(factionManager, blockedWordService,
                groupHandler, privateMessageHandler);
        when(blockedWordService.handleBlockedWords(any(), any())).thenReturn(false);
        when(groupPermissionService.invalidAdmin(anyLong(), any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("at目标后直接接#月份：策略收到at标记与目标月份")
    void dispatch_atThenMonth_parsesAtTargetAndMonth() {
        dispatcher.dispatch(groupRawMessage("g#OC收益", "#2026-07"));

        assertTrue(strategy.supportsAtUserTarget());
        assertTrue(strategy.handleQueryInvoked);
        assertFalse(strategy.formatIntroInvoked);
        assertEquals(QqCommandMessage.buildAtMarker(AT_TARGET_QQ), strategy.targetText);
        assertEquals(YearMonth.of(2026, 7), strategy.month);
    }

    @Test
    @DisplayName("at卡片自动补空格后接#月份：命令段空格被容忍，解析不受影响")
    void dispatch_atSpaceThenMonth_parsesAtTargetAndMonth() {
        dispatcher.dispatch(groupRawMessage("g#OC收益", " #2026-07"));

        assertTrue(strategy.handleQueryInvoked);
        assertFalse(strategy.formatIntroInvoked);
        assertEquals(QqCommandMessage.buildAtMarker(AT_TARGET_QQ), strategy.targetText);
        assertEquals(YearMonth.of(2026, 7), strategy.month);
    }

    @Test
    @DisplayName("月份后接at目标（at在消息末尾）：同样解析出at标记与目标月份")
    void dispatch_monthThenAt_parsesAtTargetAndMonth() {
        String rawMessage = "{\"message_type\":\"group\",\"group_id\":" + GROUP_ID
                + ",\"user_id\":" + SENDER_QQ + ",\"message\":["
                + "{\"type\":\"text\",\"data\":{\"text\":\"g#OC收益#2026-07\"}},"
                + "{\"type\":\"at\",\"data\":{\"qq\":\"" + AT_TARGET_QQ + "\"}},"
                + "{\"type\":\"text\",\"data\":{\"text\":\" \"}}]}";

        dispatcher.dispatch(rawMessage);

        assertTrue(strategy.handleQueryInvoked);
        assertFalse(strategy.formatIntroInvoked);
        assertEquals(QqCommandMessage.buildAtMarker(AT_TARGET_QQ), strategy.targetText);
        assertEquals(YearMonth.of(2026, 7), strategy.month);
    }

    @Test
    @DisplayName("真实客户端JSON：at前后均带分隔符时解析历史月份")
    void dispatch_realClientJson_parsesAtTargetAndMonth() {
        String rawMessage = "{\"self_id\":3626439891,\"user_id\":408114278,\"time\":1788063960,"
                + "\"message_id\":723531320,\"message_type\":\"group\","
                + "\"sender\":{\"user_id\":408114278,\"nickname\":\"、特困生\","
                + "\"card\":\"NoZuoNoDie [3312605]\",\"role\":\"owner\"},"
                + "\"raw_message\":\"g#oc收益#[CQ:at,qq=3854674049] #2026-07\","
                + "\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"g#oc收益#\"}},"
                + "{\"type\":\"at\",\"data\":{\"qq\":\"3854674049\"}},"
                + "{\"type\":\"text\",\"data\":{\"text\":\" #2026-07\"}}],"
                + "\"message_format\":\"array\",\"post_type\":\"message\","
                + "\"group_id\":782024117,\"group_name\":\"金眼开发测试群\"}";

        dispatcher.dispatch(rawMessage);

        assertTrue(strategy.handleQueryInvoked);
        assertFalse(strategy.formatIntroInvoked);
        assertEquals(QqCommandMessage.buildAtMarker(3854674049L), strategy.targetText);
        assertEquals(YearMonth.of(2026, 7), strategy.month);
    }

    /**
     * 构造"文本+at+文本"形态的群聊原始JSON消息。
     *
     * @param beforeAt at前的文本段
     * @param afterAt  at后的文本段
     * @return 原始JSON消息
     */
    private String groupRawMessage(String beforeAt, String afterAt) {
        return "{\"message_type\":\"group\",\"group_id\":" + GROUP_ID
                + ",\"user_id\":" + SENDER_QQ + ",\"message\":["
                + "{\"type\":\"text\",\"data\":{\"text\":\"" + beforeAt + "\"}},"
                + "{\"type\":\"at\",\"data\":{\"qq\":\"" + AT_TARGET_QQ + "\"}},"
                + "{\"type\":\"text\",\"data\":{\"text\":\"" + afterAt + "\"}}]}";
    }

    /**
     * 测试用OC收益策略桩：记录基类解析结果。
     */
    private static class StubOcBenefitStrategy extends BaseOcBenefitQueryStrategy {
        private String targetText;
        private YearMonth month;
        private boolean handleQueryInvoked;
        private boolean formatIntroInvoked;

        @Override
        public String getCommand() {
            return "OC收益";
        }

        @Override
        public String getCommandDescription() {
            return "stub";
        }

        @Override
        protected List<? extends QqMsgParam<?>> handleQuery(QqRecMsgSender sender, String targetText, YearMonth month) {
            handleQueryInvoked = true;
            this.targetText = targetText;
            this.month = month;
            return List.of(new TextQqMsg("ok"));
        }

        @Override
        protected String buildFormatIntroMsg() {
            formatIntroInvoked = true;
            return "格式介绍";
        }
    }
}
