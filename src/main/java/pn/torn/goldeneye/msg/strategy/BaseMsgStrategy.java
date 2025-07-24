package pn.torn.goldeneye.msg.strategy;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.configuration.BotSocketClient;
import pn.torn.goldeneye.msg.send.GroupMsgSocketBuilder;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;

/**
 * 基础消息策略
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
public abstract class BaseMsgStrategy {
    @Resource
    private BotSocketClient client;

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
     */
    public abstract void handle(String msg);

    /**
     * 发送文本消息
     *
     * @param msg 消息内容
     */
    protected void sendTextMsg(String msg) {
        GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(getGroupId());
        BotSocketReqParam param = builder.addMsg(new TextGroupMsg(msg)).build();
        Thread.ofVirtual().name("msg-processor", System.nanoTime()).start(() -> client.sendMessage(param));
    }

    /**
     * 发送错误格式的消息
     */
    protected void sendErrorFormatMsg() {
        sendTextMsg("参数有误");
    }
}