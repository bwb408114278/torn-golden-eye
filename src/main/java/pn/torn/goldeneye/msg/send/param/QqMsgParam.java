package pn.torn.goldeneye.msg.send.param;

import pn.torn.goldeneye.constants.bot.enums.GroupMsgTypeEnum;

/**
 * 群聊消息参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
public interface QqMsgParam<T> {
    /**
     * 获取消息类型 {@link GroupMsgTypeEnum}
     */
    String getType();

    /**
     * 获取消息数据
     *
     * @return 消息数据
     */
    T getData();
}