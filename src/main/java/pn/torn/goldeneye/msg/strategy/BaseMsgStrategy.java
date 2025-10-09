package pn.torn.goldeneye.msg.strategy;

import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;

import java.util.List;

/**
 * 基础消息策略
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
public abstract class BaseMsgStrategy {

    /**
     * 获取指令
     *
     * @return 指令
     */
    public abstract String getCommand();

    /**
     * 获取指令描述
     *
     * @return 指令描述
     */
    public abstract String getCommandDescription();

    /**
     * 处理消息
     *
     * @param sender  消息发送人
     * @param msg     消息
     * @return 需要发送的消息，为空则为不发送
     */
    public abstract List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg);

    /**
     * 发送文本消息
     *
     * @param msg 消息内容
     */
    public List<TextQqMsg> buildTextMsg(String msg) {
        return List.of(new TextQqMsg(msg));
    }

    /**
     * 发送图片消息
     *
     * @param base64 图片Base64
     */
    protected List<ImageQqMsg> buildImageMsg(String base64) {
        return List.of(new ImageQqMsg(base64));
    }

    /**
     * 发送错误格式的消息
     */
    protected List<TextQqMsg> sendErrorFormatMsg() {
        return buildTextMsg("参数有误");
    }
}