package pn.torn.goldeneye.configuration.socket.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.configuration.socket.event.BotSendMessageEvent;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;

/**
 * 机器人回复消息逻辑层
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.05.20
 */
@Component
@RequiredArgsConstructor
public class BotReplyService {
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 回复群消息
     */
    public void replyGroup(TornFactionBO faction, BotSocketReqParam param) {
        boolean factionMsgValid = faction != null && !faction.getMsgBlock();
        reply(faction == null || factionMsgValid, param);
    }

    /**
     * 回复私聊消息
     */
    public void replyPrivate(BotSocketReqParam param) {
        reply(true, param);
    }

    /**
     * 统一发送入口
     */
    private void reply(boolean valid, BotSocketReqParam param) {
        if (!valid) {
            return;
        }
        applicationEventPublisher.publishEvent(new BotSendMessageEvent(param));
    }
}