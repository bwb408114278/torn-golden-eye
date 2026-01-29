package pn.torn.goldeneye.msg.strategy.base;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

import java.util.List;

/**
 * VIP消息策略
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
public abstract class BaseVipMsgStrategy extends BasePrivateMsgStrategy {
    @Resource
    protected ProjectProperty projectProperty;

    @Override
    public List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, "");
        if (!isVip(user)) {
            return List.of(new TextQqMsg("未订阅VIP或已过期, 发送2Xan到3312605, 并备注"
                    + TornConstants.REMARK_SUBSCRIBE + "支持一次订阅多月" +
                    "\n如是加群功能申请QQ群" + projectProperty.getVipGroupId() + ", 金眼会自动通过入群申请"));
        }

        return handle(user, msg);
    }

    /**
     * 校验用户是否是VIP用户
     */
    private boolean isVip(TornUserDO user) {
        if (projectProperty.getAdminId().contains(user.getId())) {
            return true;
        }

        return user.getId().equals(3267881L);
    }

    /**
     * 处理消息
     *
     * @param user 消息发送人
     * @param msg  消息
     * @return 需要发送的消息，为空则为不发送
     */
    protected abstract List<? extends QqMsgParam<?>> handle(TornUserDO user, String msg);
}