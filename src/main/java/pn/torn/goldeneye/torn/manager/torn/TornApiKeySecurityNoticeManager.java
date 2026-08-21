package pn.torn.goldeneye.torn.manager.torn;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornQqUserManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Torn Api Key安全提醒公共逻辑层
 *
 * @author Bai
 * @version 1.3.7
 * @since 2026.08.20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornApiKeySecurityNoticeManager {
    private final Bot bot;
    private final TornSettingFactionManager factionManager;
    private final TornQqUserManager qqUserManager;
    private final SysSettingManager sysSettingManager;

    /**
     * 当Torn API返回错误码5时，向Key所属帮派群提醒用户注意Api Key安全。
     *
     * @param apiKey 触发错误的Api Key
     * @param uri    触发错误的请求地址
     */
    public void noticeApiKeySecurity(TornApiKeyDO apiKey, String uri) {
        Long factionId = apiKey.getFactionId();
        if (factionId == null) {
            log.error("Torn API返回错误码5，无法确定Api Key所属帮派，无法提醒用户注意Api Key安全, uri: {}, Key ID: {}",
                    uri, apiKey.getId());
            return;
        }

        TornSettingFactionDO faction = factionManager.getIdMap().get(factionId);
        if (faction == null || faction.getGroupId() == null || faction.getGroupId() <= 0L) {
            log.error("Torn API返回错误码5，未找到帮派群配置，无法提醒用户注意Api Key安全, uri: {}, factionId: {}",
                    uri, factionId);
            return;
        }

        long groupId = faction.getGroupId();
        if (!isBotInGroup(groupId)) {
            log.error("Torn API返回错误码5，机器人未加入帮派群，无法提醒用户注意Api Key安全, uri: {}, factionId: {}, groupId: {}",
                    uri, factionId, groupId);
            return;
        }

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(groupId)
                .addMsg(buildNoticeMsg(apiKey))
                .build();
        ResponseEntity<String> response = bot.sendRequest(param, String.class);
        if (response == null || response.getBody() == null) {
            log.error("Torn API返回错误码5，帮派群消息发送失败，无法提醒用户注意Api Key安全, uri: {}, factionId: {}, groupId: {}",
                    uri, factionId, groupId);
            return;
        }

        log.info("已向帮派群提醒用户注意Torn Api Key安全, uri: {}, factionId: {}, groupId: {}",
                uri, factionId, groupId);
    }

    /**
     * 检查机器人是否已加入指定群聊。
     */
    private boolean isBotInGroup(long groupId) {
        try {
            List<Long> memberIdList = qqUserManager.getGroupQqIdList(groupId);
            List<Long> botIdList = sysSettingManager.getBotId();
            return memberIdList.stream().anyMatch(botIdList::contains);
        } catch (Exception e) {
            log.error("检查机器人是否在帮派群时出错, groupId: {}", groupId, e);
            return false;
        }
    }

    /**
     * 构建群提醒消息，优先@对应的Key用户。
     */
    private List<QqMsgParam<?>> buildNoticeMsg(TornApiKeyDO apiKey) {
        List<QqMsgParam<?>> msgList = new ArrayList<>();
        Long qqId = apiKey.getQqId();
        msgList.add(new AtQqMsg(qqId));
        msgList.add(new TextQqMsg(" 检测到Api请求过于频繁(1分钟内超过100次)，请检查Api Key是否泄露并注意安全。"));

        return msgList;
    }
}
