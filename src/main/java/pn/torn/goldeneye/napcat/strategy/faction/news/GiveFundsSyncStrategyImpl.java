package pn.torn.goldeneye.napcat.strategy.faction.news;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.armory.FactionGiveFundsManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步取钱记录实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Component
@RequiredArgsConstructor
public class GiveFundsSyncStrategyImpl extends BaseGroupMsgStrategy {
    private final TornSettingFactionManager factionManager;
    private final FactionGiveFundsManager giveFundsManager;

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public String getCommand() {
        return BotCommands.GIVE_FUNDS;
    }

    @Override
    public String getCommandDescription() {
        return "强制刷新帮派取钱记录，慎用（格式不告诉你）";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 3) {
            return super.sendErrorFormatMsg();
        }

        TornSettingFactionDO faction = factionManager.getIdMap().get(Long.parseLong(msgArray[0]));
        LocalDateTime from = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[1]));
        LocalDateTime to = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[2]));
        if (from.isAfter(to)) {
            return super.sendErrorFormatMsg();
        }

        giveFundsManager.spiderGiveFundsData(faction, from, to);
        return super.buildTextMsg("同步" + faction.getFactionShortName() + " " +
                DateTimeUtils.convertToString(from) +
                "到" +
                DateTimeUtils.convertToString(to) +
                "的帮派取钱记录完成");
    }
}