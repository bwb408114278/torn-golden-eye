package pn.torn.goldeneye.napcat.strategy.faction.attack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalTime;
import java.util.List;

/**
 * RW解散时间策略实现类
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.04.22
 */
@Component
@RequiredArgsConstructor
public class FactionRwDisbandStrategyImpl extends PnManageMsgStrategy {
    private final TornFactionRwDAO rwDao;

    @Override
    public String getCommand() {
        return BotCommands.RW_DISBAND_TIME;
    }

    @Override
    public String getCommandDescription() {
        return "解散睡觉!明早偷袭!";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.WAR_COMMANDER;
    }

    @Override
    public List<Long> getCustomGroupId() {
        return List.of(projectProperty.getGroupId(), BotConstants.GROUP_CCRC_ID, BotConstants.GROUP_SH_ID);
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!NumberUtils.isInt(msg)) {
            return super.buildTextMsg("正确格式为g#" + BotCommands.RW_DISBAND_TIME + "#几点");
        }

        int hour = Integer.parseInt(msg);
        if (hour < 0 || hour > 23) {
            return super.sendErrorFormatMsg();
        }

        TornUserDO user = super.getTornUser(sender, "");
        TornFactionRwDO rw = rwDao.lambdaQuery()
                .eq(TornFactionRwDO::getFactionId, user.getFactionId())
                .isNull(TornFactionRwDO::getEndTime)
                .one();
        if (rw == null) {
            return super.buildTextMsg("未查询到已登记的真赛");
        }

        rwDao.lambdaUpdate()
                .set(TornFactionRwDO::getDisbandTime, LocalTime.of(hour, 0, 0))
                .eq(TornFactionRwDO::getId, rw.getId())
                .update();
        return super.buildTextMsg("解散时间已调整为" + msg + "点");
    }
}