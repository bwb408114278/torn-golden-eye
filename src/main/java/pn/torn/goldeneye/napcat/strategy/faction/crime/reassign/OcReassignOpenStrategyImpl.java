package pn.torn.goldeneye.napcat.strategy.faction.crime.reassign;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.service.faction.oc.reassign.OcReassignConfigService;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * OC大锅饭开启策略实现类
 *
 * <p>仅超管可用，为指定帮派开通大锅饭并设定收益模式（系数/平分）。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Component
@RequiredArgsConstructor
public class OcReassignOpenStrategyImpl extends BaseGroupMsgStrategy {
    /**
     * 指令用法示例（帮派ID必填，收益模式系数/平分二选一）
     */
    private static final String USAGE_EXAMPLE = "g#" + BotCommands.OC_REASSIGN_OPEN + "#帮派ID#系数|平分";

    private final OcReassignConfigService reassignConfigService;
    private final TornSettingFactionManager settingFactionManager;

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
        return BotCommands.OC_REASSIGN_OPEN;
    }

    @Override
    public String getCommandDescription() {
        return "开启帮派大锅饭(仅超管), 例" + USAGE_EXAMPLE;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length != 2 || !NumberUtils.isLong(msgArray[0])) {
            return super.buildTextMsg("参数有误，正确格式：" + USAGE_EXAMPLE);
        }

        TornOcIncomeModeEnum mode = TornOcIncomeModeEnum.ofLabel(msgArray[1].trim());
        if (mode == null) {
            return super.buildTextMsg("参数有误，正确格式：" + USAGE_EXAMPLE);
        }

        long factionId = Long.parseLong(msgArray[0]);
        OcReassignConfigService.OpenResult result =
                reassignConfigService.openFaction(factionId, mode, sender.getUserId());
        if (!result.success()) {
            return super.buildTextMsg(result.failureReason());
        }

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        String factionLabel = faction == null ? String.valueOf(factionId) : faction.getFactionShortName();
        return super.buildTextMsg("大锅饭已开启: " + factionLabel + "(" + factionId + ") 模式=" + mode.getLabel());
    }
}
