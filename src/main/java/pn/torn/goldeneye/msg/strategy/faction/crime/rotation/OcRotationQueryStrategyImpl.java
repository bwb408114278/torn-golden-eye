package pn.torn.goldeneye.msg.strategy.faction.crime.rotation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 获取OC轮转队实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.09.01
 */
@Component
@RequiredArgsConstructor
public class OcRotationQueryStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcDAO ocDao;
    private final SysSettingDAO settingDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_ROTATION_QUERY;
    }

    @Override
    public String getCommandDescription() {
        return "查询今日轮转队, g#" + BotCommands.OC_ROTATION_QUERY + "#级别";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!NumberUtils.isInt(msg)) {
            return super.sendErrorFormatMsg();
        }

        int rank = Integer.parseInt(msg);
        if (!TornConstants.ROTATION_OC_RANK.contains(rank)) {
            return super.buildTextMsg("只支持7/8级轮转队查询");
        }

        String planId = settingDao.querySettingValue(SettingConstants.KEY_OC_PLAN_ID + rank);
        TornFactionOcDO planOc = ocDao.getById(Long.parseLong(planId));

        String blockRank = settingDao.querySettingValue(SettingConstants.KEY_OC_BLOCK_RANK + rank);
        Integer newTeamRank = TornConstants.ROTATION_OC_RANK.stream().filter(r -> !r.equals(Integer.parseInt(blockRank))).findAny().orElse(null);

        String enableRank = settingDao.querySettingValue(SettingConstants.KEY_OC_ENABLE_RANK + rank);

        return super.buildTextMsg(rank + "级轮转队信息如下: " +
                "\n可加入时间: " + DateTimeUtils.convertToString(planOc.getReadyTime()) +
                "\n可开新队: " + newTeamRank + "级" +
                "\n已有的" + enableRank.replace(",", "/") + "级队可进");
    }
}