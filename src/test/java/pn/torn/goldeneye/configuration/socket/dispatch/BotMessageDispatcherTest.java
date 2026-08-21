package pn.torn.goldeneye.configuration.socket.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.socket.handler.GroupMessageHandler;
import pn.torn.goldeneye.configuration.socket.handler.PrivateMessageHandler;
import pn.torn.goldeneye.configuration.socket.service.BlockedWordService;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 机器人消息调度器 at 命令识别测试。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotMessageDispatcher at 命令识别测试")
class BotMessageDispatcherTest {

    @Mock
    private TornSettingFactionManager factionManager;
    @Mock
    private BlockedWordService blockedWordService;
    @Mock
    private GroupMessageHandler groupMessageHandler;
    @Mock
    private PrivateMessageHandler privateMessageHandler;

    @Test
    @DisplayName("真实 text + at + text(空格) 群消息识别为命令并传递 at 标记")
    void dispatch_groupTextAtSpace_shouldPassAtMarkerToGroupHandler() {
        BotMessageDispatcher dispatcher = new BotMessageDispatcher(
                factionManager, blockedWordService, groupMessageHandler, privateMessageHandler);
        when(factionManager.getByGroup(111L)).thenReturn(new TornFactionBO(new TornSettingFactionDO()));
        when(blockedWordService.handleBlockedWords(any(), any())).thenReturn(false);

        dispatcher.dispatch("""
                {
                  "message_type":"group",
                  "group_id": 111,
                  "user_id": 999,
                  "raw_message": "g#战力增长#[CQ:at,qq=12345] ",
                  "message": [
                    {"type": "text", "data": {"text": "g#战力增长#"}},
                    {"type": "at", "data": {"qq": "12345"}},
                    {"type": "text", "data": {"text": " "}}
                  ],
                  "message_format": "array"
                }
                """);

        ArgumentCaptor<QqRecMsg> msgCaptor = ArgumentCaptor.forClass(QqRecMsg.class);
        ArgumentCaptor<String[]> arrayCaptor = ArgumentCaptor.forClass(String[].class);
        verify(groupMessageHandler).handle(msgCaptor.capture(), arrayCaptor.capture(),
                eq(QqCommandMessage.buildAtMarker(12345L)), any());
        assertArrayEquals(new String[]{"g", "战力增长", ""}, arrayCaptor.getValue());
        assertEquals(111L, msgCaptor.getValue().getGroupId());
    }

    @Test
    @DisplayName("纯文本群命令仍按原参数传递且 at 标记为空")
    void dispatch_groupPureText_shouldPassEmptyAtMarker() {
        BotMessageDispatcher dispatcher = new BotMessageDispatcher(
                factionManager, blockedWordService, groupMessageHandler, privateMessageHandler);
        when(factionManager.getByGroup(222L)).thenReturn(new TornFactionBO(new TornSettingFactionDO()));
        when(blockedWordService.handleBlockedWords(any(), any())).thenReturn(false);

        dispatcher.dispatch("""
                {
                  "message_type":"group",
                  "group_id": 222,
                  "user_id": 999,
                  "raw_message": "g#战力增长#12345",
                  "message": [
                    {"type": "text", "data": {"text": "g#战力增长#12345"}}
                  ],
                  "message_format": "array"
                }
                """);

        ArgumentCaptor<String[]> arrayCaptor = ArgumentCaptor.forClass(String[].class);
        verify(groupMessageHandler).handle(any(), arrayCaptor.capture(), eq(""), any());
        assertArrayEquals(new String[]{"g", "战力增长", "12345"}, arrayCaptor.getValue());
    }
}
