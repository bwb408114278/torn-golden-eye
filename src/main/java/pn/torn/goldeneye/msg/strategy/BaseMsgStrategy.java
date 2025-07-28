package pn.torn.goldeneye.msg.strategy;

import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;

import java.util.List;

/**
 * 基础消息策略
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
public abstract class BaseMsgStrategy {
    /**
     * 获取群ID
     *
     * @return 群ID
     */
    public abstract long getGroupId();

    /**
     * 获取指令
     *
     * @return 指令
     */
    public abstract String getCommand();

    /**
     * 处理消息
     *
     * @param msg 消息
     * @return 需要发送的消息，为空则为不发送
     */
    public abstract List<? extends GroupMsgParam<?>> handle(String msg);

    /**
     * 发送文本消息
     *
     * @param msg 消息内容
     */
    protected List<TextGroupMsg> buildTextMsg(String msg) {
        return List.of(new TextGroupMsg(msg));
    }

    /**
     * 发送错误格式的消息
     */
    protected List<TextGroupMsg> sendErrorFormatMsg() {
        return buildTextMsg("参数有误");
    }
}