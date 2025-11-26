package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcCheckStrategyImpl extends BaseGroupMsgStrategy {
    private final TornFactionOcService ocService;

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
        return BotCommands.OC_CHECK;
    }

    @Override
    public String getCommandDescription() {
        return "强制刷新当前执行中OC";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (StringUtils.hasText(msg) && !NumberUtils.isInt(msg)) {
            return super.sendErrorFormatMsg();
        }

        int pageSize = StringUtils.hasText(msg) ? Integer.parseInt(msg) : 1;
        ocService.refreshOc(pageSize);
        return super.buildTextMsg("OC数据校准完成");
    }
}