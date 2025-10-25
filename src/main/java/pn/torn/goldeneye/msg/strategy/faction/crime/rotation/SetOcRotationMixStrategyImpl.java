package pn.torn.goldeneye.msg.strategy.faction.crime.rotation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;

import java.util.List;

/**
 * OC混编设置抽象实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.25
 */
@Component
@RequiredArgsConstructor
public class SetOcRotationMixStrategyImpl extends PnManageMsgStrategy {
    private final TornFactionOcService ocService;
    private final SysSettingDAO settingDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_RORATION_MIX;
    }

    @Override
    public String getCommandDescription() {
        return "将8级轮转队改为7、8级混编";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String newTeamBlockRank = settingDao.querySettingValue(SettingConstants.KEY_OC_BLOCK_RANK + "8");
        if ("8".equals(newTeamBlockRank)) {
            return super.buildTextMsg("已经是混编队了");
        }

        settingDao.updateSetting(SettingConstants.KEY_OC_BLOCK_RANK + "8", "8");
        settingDao.updateSetting(SettingConstants.KEY_OC_ENABLE_RANK + "8", "7,8");
        ocService.scheduleRotationTask();
        return super.buildTextMsg("8级轮转已改为7、8混编");
    }
}