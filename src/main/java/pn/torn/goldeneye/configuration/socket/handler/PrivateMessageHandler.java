package pn.torn.goldeneye.configuration.socket.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.socket.service.BotReplyService;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.send.msg.PrivateMsgSocketBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BasePrivateMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.manage.PrivateDocStrategyImpl;

import java.util.List;

/**
 * 私聊消息处理器
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.05.20
 */
@Component
@RequiredArgsConstructor
public class PrivateMessageHandler {
    private final List<BasePrivateMsgStrategy> privateMsgStrategyList;
    private final PrivateDocStrategyImpl privateDocStrategy;
    private final BotReplyService botReplyService;

    /**
     * 处理私聊消息
     */
    public void handle(QqRecMsg msg, String[] msgArray) {
        if (!StringUtils.hasText(msgArray[1])) {
            replyDocMessage(msg, msgArray);
            return;
        }

        BasePrivateMsgStrategy strategy = findStrategy(msgArray[1]);
        if (strategy == null) {
            return;
        }

        List<? extends QqMsgParam<?>> paramList = strategy.handle(msg.getSender(),
                msgArray.length > 2 ? msgArray[2] : "");

        if (!CollectionUtils.isEmpty(paramList)) {
            PrivateMsgSocketBuilder builder = new PrivateMsgSocketBuilder().setUserId(msg.getUserId());
            paramList.forEach(builder::addMsg);
            botReplyService.replyPrivate(builder.build());
        }
    }

    /**
     * 回复手册消息
     */
    private void replyDocMessage(QqRecMsg msg, String[] msgArray) {
        PrivateMsgSocketBuilder builder = new PrivateMsgSocketBuilder().setUserId(msg.getUserId());
        List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, privateDocStrategy);
        paramList.forEach(builder::addMsg);
        botReplyService.replyPrivate(builder.build());
    }

    /**
     * 寻找消息执行策略
     */
    private BasePrivateMsgStrategy findStrategy(String command) {
        for (BasePrivateMsgStrategy strategy : privateMsgStrategyList) {
            if (strategy.getCommand().equalsIgnoreCase(command)) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * 构建私聊帮助/文档回复
     * <p>
     * 如果你的 BasePrivateMsgStrategy 没有 buildTextMsg，则按你的项目实际改。
     */
    private List<? extends QqMsgParam<?>> buildReplyMsg(QqRecMsg msg, String[] msgArray,
                                                        BasePrivateMsgStrategy strategy) {
        try {
            return strategy.handle(msg.getSender(), msgArray.length > 2 ? msgArray[2] : "");
        } catch (BizException e) {
            if (strategy instanceof PrivateDocStrategyImpl privateDoc) {
                return privateDoc.buildTextMsg(e.getMsg());
            }
            return List.of(new TextQqMsg(e.getMsg()));
        }
    }
}