package pn.torn.goldeneye.napcat.strategy.base;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;

import java.time.LocalDate;
import java.util.List;

/**
 * VIP消息策略
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.01.29
 */
public abstract class BaseVipMsgStrategy extends BasePrivateMsgStrategy {
    @Resource
    private VipSubscribeDAO subscribeDao;
    @Resource
    private ProjectProperty projectProperty;

    @Override
    public List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, "");
        if (!isVip(user)) {
            return List.of(new TextQqMsg("当前QQ没有有效的订阅记录。"
                    + "\n金眼的部分功能需要开通后使用，请先加入金眼交流群，开通方式见群公告。"
                    + "\n金眼交流群：" + projectProperty.getVipEntryGroupId()
                    + "\n提醒：金眼不会向任何人索要游戏账号、密码或游戏道具。"));
        }

        return handle(user, msg);
    }

    /**
     * 校验用户是否是VIP用户
     */
    private boolean isVip(TornUserDO user) {
        VipSubscribeDO subscribe = subscribeDao.lambdaQuery().eq(VipSubscribeDO::getQqId, user.getQqId()).one();
        if (subscribe == null) {
            return false;
        }

        return subscribe.getEndDate() == null || !subscribe.getEndDate().isBefore(LocalDate.now());
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