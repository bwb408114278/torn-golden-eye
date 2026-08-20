package pn.torn.goldeneye.torn.manager.torn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgReqParam;
import pn.torn.goldeneye.napcat.send.msg.data.AtMsgData;
import pn.torn.goldeneye.napcat.send.msg.data.TextMsgData;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornQqUserManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Torn Api Key安全提醒公共逻辑层测试
 *
 * @author Bai
 * @version 1.3.7
 * @since 2026.08.20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Torn Api Key安全提醒测试")
class TornApiKeySecurityNoticeManagerTest {

    private static final long FACTION_ID = 100L;
    private static final long GROUP_ID = 888888L;
    private static final long USER_QQ = 11111L;
    private static final long BOT_QQ = 22222L;

    @Mock
    private Bot bot;
    @Mock
    private TornSettingFactionManager factionManager;
    @Mock
    private TornQqUserManager qqUserManager;
    @Mock
    private SysSettingManager sysSettingManager;

    @InjectMocks
    private TornApiKeySecurityNoticeManager noticeManager;

    @Test
    @DisplayName("机器人在帮派群时发送安全提醒并@对应用户")
    void noticeApiKeySecurity_botInGroup_sendsGroupMessageWithAtUser() {
        when(factionManager.getIdMap()).thenReturn(Map.of(FACTION_ID, faction(GROUP_ID)));
        when(qqUserManager.getGroupQqIdList(GROUP_ID)).thenReturn(List.of(USER_QQ, BOT_QQ));
        when(sysSettingManager.getBotId()).thenReturn(List.of(BOT_QQ));
        when(bot.sendRequest(any(), eq(String.class))).thenReturn(ResponseEntity.ok("ok"));

        noticeManager.noticeApiKeySecurity(apiKey(), "/api/v2/user");

        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.captor();
        verify(bot).sendRequest(paramCaptor.capture(), eq(String.class));
        GroupMsgReqParam body = (GroupMsgReqParam) paramCaptor.getValue().body();
        assertEquals(GROUP_ID, body.getGroupId(), "提醒必须发送到Key所属帮派群");

        AtMsgData atData = body.getMessage().stream()
                .filter(msg -> msg.getData() instanceof AtMsgData)
                .map(msg -> (AtMsgData) msg.getData())
                .findFirst()
                .orElseThrow();
        assertEquals(USER_QQ, atData.qq(), "必须@触发错误的用户");

        TextMsgData textData = body.getMessage().stream()
                .filter(msg -> msg.getData() instanceof TextMsgData)
                .map(msg -> (TextMsgData) msg.getData())
                .findFirst()
                .orElseThrow();
        assertTrue(textData.text().contains("Api Key"), "提醒文本必须包含Api Key安全提示: " + textData.text());
    }

    @Test
    @DisplayName("机器人未加入帮派群时不发送提醒")
    void noticeApiKeySecurity_botNotInGroup_doesNotSend() {
        when(factionManager.getIdMap()).thenReturn(Map.of(FACTION_ID, faction(GROUP_ID)));
        when(qqUserManager.getGroupQqIdList(GROUP_ID)).thenReturn(List.of(USER_QQ));
        when(sysSettingManager.getBotId()).thenReturn(List.of(BOT_QQ));

        noticeManager.noticeApiKeySecurity(apiKey(), "/api/v2/user");

        verify(bot, never()).sendRequest(any(), any());
    }

    @Test
    @DisplayName("帮派未配置群时不发送提醒")
    void noticeApiKeySecurity_groupNotConfigured_doesNotSend() {
        when(factionManager.getIdMap()).thenReturn(Map.of(FACTION_ID, faction(0L)));

        noticeManager.noticeApiKeySecurity(apiKey(), "/api/v2/user");

        verify(bot, never()).sendRequest(any(), any());
        verify(qqUserManager, never()).getGroupQqIdList(anyLong());
    }

    private TornApiKeyDO apiKey() {
        TornApiKeyDO apiKey = new TornApiKeyDO();
        apiKey.setId(1L);
        apiKey.setUserId(200001L);
        apiKey.setFactionId(FACTION_ID);
        apiKey.setQqId(USER_QQ);
        return apiKey;
    }

    private TornSettingFactionDO faction(long groupId) {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(FACTION_ID);
        faction.setGroupId(groupId);
        return faction;
    }
}
