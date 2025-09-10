package pn.torn.goldeneye.msg.strategy;

import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.torn.TornUserUtils;

import java.util.List;

/**
 * 基础群消息策略
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
public abstract class BaseGroupMsgStrategy extends BaseMsgStrategy {
    /**
     * 是否需要管理员权限
     *
     * @return true为需要管理员
     */
    public boolean isNeedAdmin() {
        return true;
    }

    /**
     * 处理消息
     *
     * @param groupId 群聊ID
     * @param sender  消息发送人
     * @param msg     消息
     * @return 需要发送的消息，为空则为不发送
     */
    public abstract List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg);

    @Override
    public List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
        return List.of();
    }

    /**
     * 根据消息和发送人获取用户ID
     */
    protected long getTornUserId(QqRecMsgSender sender, String msg) {
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                throw new BizException("参数有误");
            }

            return Long.parseLong(msgArray[0]);
        } else {
            return TornUserUtils.getUserIdFromSender(sender);
        }
    }
}