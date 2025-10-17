package pn.torn.goldeneye.msg.strategy.base;

import jakarta.annotation.Resource;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.torn.TornUserUtils;

import java.util.List;

/**
 * 基础群消息策略
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
public abstract class BaseGroupMsgStrategy extends BaseMsgStrategy {
    @Resource
    protected TornUserDAO userDao;

    /**
     * 群定制功能的群号
     *
     * @return 群号
     */
    public long getCustomGroupId() {
        return 0L;
    }

    /**
     * 是否需要管理员权限
     *
     * @return true为需要管理员
     */
    public boolean isNeedAdmin() {
        return true;
    }

    /**
     * 是否需要超管权限
     *
     * @return true为需要超管
     */
    public boolean isNeedSa() {
        return false;
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
    protected TornUserDO getTornUser(QqRecMsgSender sender, String msg) {
        long userId;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                throw new BizException("参数有误");
            }

            userId = Long.parseLong(msgArray[0]);
        } else {
            userId = TornUserUtils.getUserIdFromSender(sender);
        }

        if (userId == 0L) {
            throw new BizException("金蝶不认识你哦");
        }

        TornUserDO user = userDao.getById(userId);
        if (user == null) {
            throw new BizException("金蝶不认识你哦");
        }

        return user;
    }

    /**
     * 根据消息和发送人获取帮派ID
     */
    protected long getTornFactionId(QqRecMsgSender sender, String msg) {
        long factionId;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                throw new BizException("参数有误");
            }

            factionId = Long.parseLong(msgArray[0]);
        } else {
            factionId = getTornFactionIdBySender(sender);
        }

        if (factionId == 0L) {
            throw new BizException("群名片有误，中括号加了吗");
        }

        return factionId;
    }

    /**
     * 根据发送人获取帮派ID
     */
    protected long getTornFactionIdBySender(QqRecMsgSender sender) {
        long userId = TornUserUtils.getUserIdFromSender(sender);
        TornUserDO user = userDao.getById(userId);
        return user == null ? 0L : user.getFactionId();
    }
}