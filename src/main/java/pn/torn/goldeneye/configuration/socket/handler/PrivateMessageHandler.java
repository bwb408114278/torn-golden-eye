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
 * @version 1.4.0
 * @since 2026.05.20
 */
@Component
@RequiredArgsConstructor
public class PrivateMessageHandler {
    private final List<BasePrivateMsgStrategy> privateMsgStrategyList;
    private final PrivateDocStrategyImpl privateDocStrategy;
    private final BotReplyService botReplyService;

    /**
     * 处理私聊消息（兼容无 at 标记调用）。
     *
     * @param msg      入站消息
     * @param msgArray 命令数组
     */
    public void handle(QqRecMsg msg, String[] msgArray) {
        handle(msg, msgArray, "");
    }

    /**
     * 处理私聊消息。
     *
     * @param msg      入站消息
     * @param msgArray 命令数组
     * @param atMarker 内部 at 目标标记；无 at 时为空字符串
     */
    public void handle(QqRecMsg msg, String[] msgArray, String atMarker) {
        if (!StringUtils.hasText(msgArray[1])) {
            replyDocMessage(msg, msgArray, atMarker);
            return;
        }

        BasePrivateMsgStrategy strategy = findStrategy(msgArray[1]);
        if (strategy == null) {
            return;
        }

        String param = resolveParam(msgArray, atMarker, strategy);
        List<? extends QqMsgParam<?>> paramList = strategy.handle(msg.getSender(), param);

        if (!CollectionUtils.isEmpty(paramList)) {
            PrivateMsgSocketBuilder builder = new PrivateMsgSocketBuilder().setUserId(msg.getUserId());
            paramList.forEach(builder::addMsg);
            botReplyService.replyPrivate(builder.build());
        }
    }

    /**
     * 回复手册消息
     */
    private void replyDocMessage(QqRecMsg msg, String[] msgArray, String atMarker) {
        PrivateMsgSocketBuilder builder = new PrivateMsgSocketBuilder().setUserId(msg.getUserId());
        List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, atMarker, privateDocStrategy);
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
                                                        String atMarker,
                                                        BasePrivateMsgStrategy strategy) {
        try {
            String param = resolveParam(msgArray, atMarker, strategy);
            return strategy.handle(msg.getSender(), param);
        } catch (BizException e) {
            if (strategy instanceof PrivateDocStrategyImpl privateDoc) {
                return privateDoc.buildTextMsg(e.getMsg());
            }
            return List.of(new TextQqMsg(e.getMsg()));
        }
    }

    /**
     * 组装策略参数：仅用户查询策略接收内部 at 标记，其他策略保持纯文本参数不变。
     */
    private String resolveParam(String[] msgArray, String atMarker, BasePrivateMsgStrategy strategy) {
        String plainParam = msgArray.length > 2 ? msgArray[2] : "";
        if (!StringUtils.hasText(atMarker) || strategy.notSupportsAtUserTarget()) {
            return plainParam;
        }
        return plainParam + atMarker;
    }
}