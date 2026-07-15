package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcNewTeamPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcNewTeamPlanRenderer;
import pn.torn.goldeneye.torn.service.faction.oc.planning.OcNewTeamPlanningFacade;

import java.util.List;

/**
 * OC新队规划指令。三个二级指令统一保留在同一个策略实现中。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2025.11.04
 */
@Component
@RequiredArgsConstructor
public class OcNewTeamStrategyImpl extends BaseGroupMsgStrategy {
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final OcNewTeamPlanningFacade planningFacade;
    private final OcNewTeamPlanRenderer renderer;

    @Override
    public String getCommand() {
        return BotCommands.OC_NEW_TEAM;
    }

    @Override
    public String getCommandDescription() {
        return "根据当前队伍、人员能力和链路给出OC新队方案：g#" + BotCommands.OC_NEW_TEAM
                + "#保守 / g#" + BotCommands.OC_NEW_TEAM + "#均衡 / g#"
                + BotCommands.OC_NEW_TEAM + "#收益";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.OC_COMMANDER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        final OcPlanMode mode;
        try {
            mode = OcPlanMode.parse(msg);
        } catch (IllegalArgumentException exception) {
            return super.buildTextMsg("二级指令仅支持：OC新队#保守、OC新队#均衡、OC新队#收益");
        }
        long factionId = super.getTornFactionIdBySender(sender);
        ocRefreshManager.refreshOc(1, factionId);
        OcNewTeamPlan plan = planningFacade.plan(factionId, mode);
        return super.buildTextMsg(renderer.render(plan));
    }
}
