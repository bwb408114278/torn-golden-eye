package pn.torn.goldeneye.napcat.send.msg;

import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 群聊消息构建器 - Socket
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.10
 */
@Slf4j
public class GroupMsgSocketBuilder {
    private static final String ACTION = "send_group_msg";

    /**
     * 请求参数列表
     */
    private final List<QqMsgParam<?>> paramList = new ArrayList<>();
    private long groupId = 0L;

    /**
     * 设置群号
     */
    public GroupMsgSocketBuilder setGroupId(long groupId) {
        this.groupId = groupId;
        return this;
    }

    /**
     * 添加消息
     */
    public GroupMsgSocketBuilder addMsg(QqMsgParam<?> param) {
        this.paramList.add(param);
        return this;
    }

    /**
     * 添加消息
     */
    public GroupMsgSocketBuilder addMsg(List<QqMsgParam<?>> param) {
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
                return new GroupMsgReqParam(groupId, paramList);
            }
        };
    }
}