package pn.torn.goldeneye.msg.strategy;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.TestProperty;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;

import java.util.List;

/**
 * 基础群消息策略
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
public abstract class BaseGroupMsgStrategy extends BaseMsgStrategy {
    @Resource
    protected TestProperty testProperty;

    /**
     * 获取群ID
     *
     * @return 群ID
     */
    public long[] getGroupId() {
        return new long[]{testProperty.getGroupId()};
    }

    /**
     * 处理消息
     *
     * @param groupId 群聊ID
     * @param sender  消息发送人
     * @param msg     消息
     * @return 需要发送的消息，为空则为不发送
     */
    public abstract List<? extends GroupMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg);

    @Override
    public List<? extends GroupMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
        return List.of();
    }
}