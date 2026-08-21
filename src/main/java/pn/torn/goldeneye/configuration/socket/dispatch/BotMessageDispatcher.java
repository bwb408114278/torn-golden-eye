package pn.torn.goldeneye.configuration.socket.dispatch;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.configuration.socket.handler.GroupMessageHandler;
import pn.torn.goldeneye.configuration.socket.handler.PrivateMessageHandler;
import pn.torn.goldeneye.configuration.socket.service.BlockedWordService;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessageParser;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;
import pn.torn.goldeneye.utils.JsonUtils;

/**
 * 机器人消息调度器
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.05.20
 */
@Component
@RequiredArgsConstructor
public class BotMessageDispatcher {
    private final TornSettingFactionManager factionManager;
    private final BlockedWordService blockedWordService;
    private final GroupMessageHandler groupMessageHandler;
    private final PrivateMessageHandler privateMessageHandler;

    /**
     * 分发原始消息
     */
    public void dispatch(String rawMessage) {
        boolean isGroupMessage = rawMessage.contains("\"message_type\":\"group\"");
        boolean isPrivateMessage = rawMessage.contains("\"message_type\":\"private\"");

        if (!isGroupMessage && !isPrivateMessage) {
            return;
        }

        QqRecMsg msg = JsonUtils.jsonToObj(rawMessage, QqRecMsg.class);
        if (msg == null || CollectionUtils.isEmpty(msg.getMessage())) {
            return;
        }

        TornFactionBO faction = isGroupMessage ? factionManager.getByGroup(msg.getGroupId()) : null;
        if (isGroupMessage && blockedWordService.handleBlockedWords(msg, faction)) {
            return;
        }

        QqCommandMessage commandMessage = QqCommandMessageParser.parse(msg);
        if (commandMessage == null) {
            return;
        }

        String[] msgArray = commandMessage.commandText().split("#", 3);
        if (msgArray.length < 2) {
            return;
        }

        if (isGroupMessage) {
            groupMessageHandler.handle(msg, msgArray, commandMessage.atMarker(), faction);
        } else {
            privateMessageHandler.handle(msg, msgArray, commandMessage.atMarker());
        }
    }
}