package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcNewTeamPlanRenderer;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcNewTeamPlanningFacade;

import java.util.List;

/**
 * OC新队规划指令。三个二级指令统一保留在同一个策略实现中。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Component
@RequiredArgsConstructor
public class OcNewTeamStrategyImpl extends BaseGroupMsgStrategy {
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final OcNewTeamPlanningFacade planningFacade;
    private final OcNewTeamPlanRenderer renderer;
    private final ProjectProperty projectProperty;

    @Override
    public String getCommand() {
        return BotCommands.OC_NEW_TEAM;
    }

    @Override
    public String getCommandDescription() {
        return "计算可开几个OC新队, 三模式: 保守/均衡/收益";
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
            return super.buildTextMsg("二级指令仅支持：g#OC新队#保守、g#OC新队#均衡、g#OC新队#收益");
        }
        long factionId = super.getTornFactionIdBySender(sender);
        if (BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            ocRefreshManager.refreshOc(1, factionId);
        }
        // 本地数据同步不等同于游戏随机结果刷新；当前产品没有已确认的随机结果变化事件入口，
        // 命令策略不得向规划器宣称随机结果已变化。
        OcRefreshInstructionPlan plan = planningFacade.plan(factionId, mode, false);
        return super.buildTextMsg(renderer.render(plan));
    }
}
