package pn.torn.goldeneye.napcat.strategy.faction.attack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
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
 * 对冲集合策略实现类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.23
 */
@Component
@RequiredArgsConstructor
public class FactionRwGatheringStrategyImpl extends PnManageMsgStrategy {
    private final TornFactionRwDAO rwDao;

    @Override
    public String getCommand() {
        return BotCommands.RW_GATHERING_TIME;
    }

    @Override
    public String getCommandDescription() {
        return "到点了!起床集合!";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.WAR_COMMANDER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!NumberUtils.isInt(msg)) {
            return super.buildTextMsg("正确格式为g#" + BotCommands.RW_GATHERING_TIME + "#几点");
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
                .set(TornFactionRwDO::getGatheringTime, LocalTime.of(hour, 0, 0))
                .eq(TornFactionRwDO::getId, rw.getId())
                .update();
        return super.buildTextMsg("集合时间已调整为" + msg + "点");
    }
}