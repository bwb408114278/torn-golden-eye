package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcAssignService;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OC分配策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.21
 */
@Component
@RequiredArgsConstructor
public class OcAssignStrategyImpl extends BaseGroupMsgStrategy {
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final TornOcAssignService ocAssignService;
    private final TornFactionOcMsgManager msgManager;
    private final TornSettingFactionManager settingFactionManager;

    @Override
    public String getCommand() {
        return BotCommands.OC_ASSIGN;
    }

    @Override
    public String getCommandDescription() {
        return "看看是谁在摸鱼不加OC";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.OC_COMMANDER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, "");
        ocRefreshManager.refreshOc(1, user.getFactionId());

        Map<TornUserDO, OcRecommendationVO> map = ocAssignService.assignUserList(user.getFactionId());
        if (CollectionUtils.isEmpty(map)) {
            return super.buildTextMsg("没有空闲的成员");
        } else if (map.values().stream().noneMatch(Objects::nonNull)) {
            StringBuilder builder = new StringBuilder();
            for (TornUserDO member : map.keySet()) {
                builder.append(", ").append(member.getNickname()).append(" [").append(member.getId()).append("]");
            }

            return super.buildTextMsg("以下玩家没有合适的队伍, 是否应该生成新队?\n" +
                    builder.toString().replaceFirst(", ", ""));
        }

        return buildRecommendTable(user, map);
    }

    /**
     * 构建建议表格
     */
    private List<ImageQqMsg> buildRecommendTable(TornUserDO user, Map<TornUserDO, OcRecommendationVO> map) {
        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(user.getFactionId());
        String title = faction.getFactionShortName() + " OC队伍分配建议";
        String table = msgManager.buildRecommendTable(title, user.getFactionId(), map);
        return super.buildImageMsg(table);
    }
}