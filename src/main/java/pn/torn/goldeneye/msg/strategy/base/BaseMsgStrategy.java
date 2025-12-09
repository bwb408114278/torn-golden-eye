package pn.torn.goldeneye.msg.strategy.base;

import jakarta.annotation.Resource;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 基础消息策略
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.07.24
 */
public abstract class BaseMsgStrategy {
    @Resource
    protected TornUserManager userManager;

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
     * @param sender 消息发送人
     * @param msg    消息
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

    /**
     * 根据消息和发送人获取用户ID
     */
    protected TornUserDO getTornUser(QqRecMsgSender sender, String msg) {
        TornUserDO user;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                throw new BizException("参数有误");
            }

            long userId = Long.parseLong(msgArray[0]);
            user = userManager.getUserById(userId);
        } else {
            user = userManager.getUserByQq(sender.getUserId());
        }

        if (user == null) {
            throw new BizException("金蝶不认识你哦");
        }

        return user;
    }

    /**
     * 根据消息和发送人获取帮派ID
     */
    protected long getTornFactionId(String msg) {
        long factionId;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                throw new BizException("参数有误");
            }

            factionId = Long.parseLong(msgArray[0]);
        } else {
            factionId = 0L;
        }

        return factionId;
    }

    /**
     * 根据发送人获取帮派ID
     */
    protected long getTornFactionIdBySender(QqRecMsgSender sender) {
        TornUserDO user = getTornUser(sender, "");
        return user == null ? 0L : user.getFactionId();
    }
}