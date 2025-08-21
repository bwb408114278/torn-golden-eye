package pn.torn.goldeneye.msg.send;

import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 私聊消息构建器 - Socket
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Slf4j
public class PrivateMsgSocketBuilder {
    private static final String ACTION = "send_private_msg";

    /**
     * 请求参数列表
     */
    private final List<QqMsgParam<?>> paramList = new ArrayList<>();
    private long userId = 0L;

    /**
     * 设置群号
     */
    public PrivateMsgSocketBuilder setUserId(long userId) {
        this.userId = userId;
        return this;
    }

    /**
     * 添加消息
     */
    public PrivateMsgSocketBuilder addMsg(QqMsgParam<?> param) {
        this.paramList.add(param);
        return this;
    }

    /**
     * 添加消息
     */
    public PrivateMsgSocketBuilder addMsg(List<QqMsgParam<?>> param) {
        this.paramList.addAll(param);
        return this;
    }

    public BotSocketReqParam build() {
        return new BotSocketReqParam() {
            @Override
            public String getAction() {
                return ACTION;
            }

            @Override
            public Object getParams() {
                return new PrivateMsgReqParam(userId, paramList);
            }
        };
    }
}