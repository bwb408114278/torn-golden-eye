package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.base.BasePrivateMsgStrategy;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 获取私聊指令手册
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.02.02
 */
@Component
@RequiredArgsConstructor
public class PrivateDocStrategyImpl extends BasePrivateMsgStrategy {
    private final ApplicationContext applicationContext;
    private final VipSubscribeDAO vipSubscribeDao;
    private final ProjectProperty projectProperty;

    @Override
    public String getCommand() {
        return BotCommands.DOC;
    }

    @Override
    public String getCommandDescription() {
        return "列出所有可用指令";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg) {
        Collection<BasePrivateMsgStrategy> privateStrategyList = applicationContext
                .getBeansOfType(BasePrivateMsgStrategy.class)
                .values();

        StringBuilder helpText = new StringBuilder("可用指令列表，以g#开头，括号内为可选参数\n");
        privateStrategyList.forEach(strategy -> appendCommandDesc(strategy, helpText));

        helpText.append("\n金眼交流群：").append(projectProperty.getVipEntryGroupId())
                .append("（开通与使用说明见群公告）")
                .append("\n提醒：金眼不会向任何人索要游戏账号、密码或游戏道具。");

        TornUserDO user = super.getTornUser(sender, "");
        helpText.append(buildVipRemainDesc(user));

        return buildTextMsg(helpText.toString());
    }

    private void appendCommandDesc(BaseMsgStrategy strategy, StringBuilder helpText) {
        helpText.append(strategy.getCommand())
                .append(" - ")
                .append(strategy.getCommandDescription())
                .append("\n");
    }

    /**
     * 构建VIP剩余时长描述
     */
    private String buildVipRemainDesc(TornUserDO user) {
        if (user == null) {
            return "";
        }

        LocalDate now = LocalDate.now();
        VipSubscribeDO vip = vipSubscribeDao.lambdaQuery().eq(VipSubscribeDO::getUserId, user.getId()).one();
        if (vip == null) {
            return "";
        } else if (vip.getEndDate() == null) {
            return "\n您还有" + vip.getSubscribeLength() + "天可以兑换(可直接申请, 进群后才开始计时)";
        } else if (now.isBefore(vip.getEndDate())) {
            return "\n您的到期时间为" + DateTimeUtils.convertToString(vip.getEndDate());
        } else {
            return "";
        }
    }
}