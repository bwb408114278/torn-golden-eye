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
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
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
public class VipNoticePauseStrategyImpl extends BaseVipMsgStrategy {
    private final VipNoticeConfigDAO configDao;

    @Override
    public String getCommand() {
        return BotCommands.VIP_NOTICE_PAUSE;
    }

    @Override
    public String getCommandDescription() {
        return "暂停Energy或躺飞提醒，适用于叠E、真赛";
    }

    @Override
    protected List<? extends QqMsgParam<?>> handle(TornUserDO user, String msg) {
        if (!StringUtils.hasText(msg)) {
            return List.of(new TextQqMsg(buildCommandFormatMsg()));
        }

        String[] msgArray = msg.split("#");
        if (msgArray.length < 2 || !StringUtils.hasText(msgArray[0]) || !NumberUtils.isInt(msgArray[1])) {
            return List.of(new TextQqMsg(buildCommandFormatMsg()));
        }

        VipNoticeConfigDO config = configDao.getOrCreate(user);
        int days = Integer.parseInt(msgArray[1]);
        LocalDateTime pauseUntil = LocalDateTime.now().plusDays(days);
        String[] typeArray = msgArray[0].split(",");
        for (String type : typeArray) {
            VipNoticeTypeEnum typeEnum = VipNoticeTypeEnum.aliasOf(type);
            if (VipNoticeTypeEnum.ENERGY.equals(typeEnum)) {
                configDao.lambdaUpdate()
                        .set(VipNoticeConfigDO::getPauseEnergyUntil, pauseUntil)
                        .eq(VipNoticeConfigDO::getId, config.getId())
                        .update();
            } else if (VipNoticeTypeEnum.TRAVEL.equals(typeEnum)) {
                configDao.lambdaUpdate()
                        .set(VipNoticeConfigDO::getPauseTravelUntil, pauseUntil)
                        .eq(VipNoticeConfigDO::getId, config.getId())
                        .update();
            }
        }

        return List.of(new TextQqMsg("设置成功, 提醒已暂停"));
    }

    private String buildCommandFormatMsg() {
        return "格式如下: g#" + BotCommands.VIP_NOTICE_PAUSE + "#类型#天数, 举例: \n" +
                "停止能量提醒2天: g#" + BotCommands.VIP_NOTICE_PAUSE + "#Engery#2\n" +
                "停止能量和躺飞提醒1天: g#" + BotCommands.VIP_NOTICE_PAUSE + "#Engery,躺飞#1";
    }
}