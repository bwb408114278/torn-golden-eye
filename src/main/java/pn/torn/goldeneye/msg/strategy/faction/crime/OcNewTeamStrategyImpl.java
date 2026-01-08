package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.model.faction.crime.create.OcNewTeamBO;
import pn.torn.goldeneye.torn.service.faction.oc.create.TornOcManageService;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建OC新队实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.11.04
 */
@Component
@RequiredArgsConstructor
public class OcNewTeamStrategyImpl extends PnManageMsgStrategy {
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final TornOcManageService ocManageService;

    @Override
    public List<Long> getCustomGroupId() {
        return List.of(projectProperty.getGroupId(),
                BotConstants.GROUP_HP_ID,
                BotConstants.GROUP_CCRC_ID,
                BotConstants.GROUP_SH_ID);
    }

    @Override
    public String getCommand() {
        return BotCommands.OC_NEW_TEAM;
    }

    @Override
    public String getCommandDescription() {
        return "获取开OC新队的建议";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.OC_COMMANDER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = super.getTornFactionIdBySender(sender);

        int hour = 8;
        if (StringUtils.hasText(msg) && !NumberUtils.isInt(msg)) {
            return super.sendErrorFormatMsg();
        } else if (StringUtils.hasText(msg)) {
            hour = Integer.parseInt(msg);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = now.plusHours(hour);

        ocRefreshManager.refreshOc(1, factionId);
        OcNewTeamBO team = ocManageService.analyze(factionId, now, target);
        return super.buildTextMsg(buildNewTeamMsg(team, hour));
    }

    /**
     * 构建新队消息
     */
    private String buildNewTeamMsg(OcNewTeamBO team, int hour) {
        return "当前可做7/8级的OC人数: " + team.getAvailableUserCount()
                + "\n当前运行的总OC数: " + team.getExecOcCount()
                + "\n当前OC新队数: " + team.getNewOcCount()
                + "\n" + hour + "小时后预计空闲人数: " + team.getFreeUserCount()
                + "\n" + hour + "小时后预计完成队伍数: " + team.getFinishCount()
                + "\n" + hour + "小时后停转队伍数: " + team.getNearByStopCount()
                + "\n===================="
                + "\n24小时内总停转队伍数: " + team.getTodayStopCount()
                + "\n将24小时内停转队伍按照权重-成功率-由低到高分配"
                + "\n" + hour + "小时后释放的人可分配" + team.getMatchSuccessUserCount() + "人入队"
                + "\n" + team.getFailMatchCount() + "队没有合适的人加入"
                + "\n剩余人中有" + team.getOnlyLowLevelUserCount() + "人只能做7级, "
                + "有" + (team.getFreeUserCount() - team.getMatchSuccessUserCount() - team.getOnlyLowLevelUserCount()) +
                "人可以同时做7/8级";
    }
}