package pn.torn.goldeneye.napcat.strategy.vip;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BaseVipMsgStrategy;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeConfigDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;

import java.util.List;

/**
 * VIP提醒暂停
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.14
 */
@Component
@RequiredArgsConstructor
public class VipNoticeResumeStrategyImpl extends BaseVipMsgStrategy {
    private final VipNoticeConfigDAO configDao;

    @Override
    public String getCommand() {
        return BotCommands.VIP_NOTICE_RESUME;
    }

    @Override
    public String getCommandDescription() {
        return "恢复Energy或躺飞提醒";
    }

    @Override
    protected List<? extends QqMsgParam<?>> handle(TornUserDO user, String msg) {
        if (!StringUtils.hasText(msg)) {
            return List.of(new TextQqMsg(buildCommandFormatMsg()));
        }

        String[] typeArray = msg.split(",");
        VipNoticeConfigDO config = configDao.getOrCreate(user);
        for (String type : typeArray) {
            VipNoticeTypeEnum typeEnum = VipNoticeTypeEnum.aliasOf(type);
            if (VipNoticeTypeEnum.ENERGY.equals(typeEnum)) {
                configDao.lambdaUpdate()
                        .set(VipNoticeConfigDO::getPauseEnergyUntil, null)
                        .eq(VipNoticeConfigDO::getId, config.getId())
                        .update();
            } else if (VipNoticeTypeEnum.TRAVEL.equals(typeEnum)) {
                configDao.lambdaUpdate()
                        .set(VipNoticeConfigDO::getPauseTravelUntil, null)
                        .eq(VipNoticeConfigDO::getId, config.getId())
                        .update();
            }
        }

        return List.of(new TextQqMsg("设置成功, 提醒已恢复"));
    }

    private String buildCommandFormatMsg() {
        return "格式如下: g#" + BotCommands.VIP_NOTICE_RESUME + "#类型, 举例: \n" +
                "恢复能量提醒: g#" + BotCommands.VIP_NOTICE_RESUME + "#Engery\n" +
                "恢复能量和躺飞提醒: g#" + BotCommands.VIP_NOTICE_RESUME + "#Engery,躺飞";
    }
}