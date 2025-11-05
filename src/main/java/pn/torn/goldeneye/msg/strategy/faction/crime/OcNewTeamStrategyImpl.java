package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.NewOcSuggestionVO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcManageService;
import pn.torn.goldeneye.utils.JsonUtils;

import java.util.List;

/**
 * 创建OC新队实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.04
 */
@Component
@RequiredArgsConstructor
public class OcNewTeamStrategyImpl extends PnManageMsgStrategy {
    private final TornFactionOcService ocService;
    private final TornOcManageService ocManageService;

    @Override
    public String getCommand() {
        return BotCommands.OC_NEW_TEAM;
    }

    @Override
    public String getCommandDescription() {
        return "获取开OC新队的建议";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        ocService.refreshOc(1, TornConstants.FACTION_PN_ID);

        NewOcSuggestionVO suggestion = ocManageService.analyzeNewOcNeeds();
        return super.buildTextMsg(suggestion.getSuggestion());
    }
}