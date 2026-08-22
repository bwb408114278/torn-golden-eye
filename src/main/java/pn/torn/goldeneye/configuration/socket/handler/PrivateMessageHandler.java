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
import pn.torn.goldeneye.napcat.strategy.base.BaseMsgStrategy;
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

        List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, atMarker, strategy);
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
     * 构建私聊回复：组装策略参数并执行策略；业务异常统一转为文本回复，
     * 与群聊的 at 拒绝和参数错误提示语义保持一致。
     */
    private List<? extends QqMsgParam<?>> buildReplyMsg(QqRecMsg msg, String[] msgArray,
                                                        String atMarker,
                                                        BasePrivateMsgStrategy strategy) {
        try {
            String param = resolveParam(msgArray, atMarker, strategy);
            return strategy.handle(msg.getSender(), param);
        } catch (BizException e) {
            return strategy.buildTextMsg(e.getMsg());
        }
    }

    /**
     * 组装策略参数：无 at 时保持纯文本参数；有 at 时仅用户查询策略接收内部 at 标记，
     * 不支持 at 的策略拒绝执行并返回稳定错误，不静默丢弃 at。
     */
    private String resolveParam(String[] msgArray, String atMarker, BasePrivateMsgStrategy strategy) {
        String plainParam = msgArray.length > 2 ? msgArray[2] : "";
        if (!StringUtils.hasText(atMarker)) {
            return plainParam;
        }
        if (!strategy.supportsAtUserTarget()) {
            throw new BizException(BaseMsgStrategy.AT_UNSUPPORTED_MSG);
        }
        return plainParam + atMarker;
    }
}