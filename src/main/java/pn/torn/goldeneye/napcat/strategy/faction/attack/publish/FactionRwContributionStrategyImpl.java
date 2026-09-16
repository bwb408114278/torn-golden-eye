package pn.torn.goldeneye.napcat.strategy.faction.attack.publish;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionReportBO;
import pn.torn.goldeneye.torn.service.faction.attack.contribution.RwContributionQueryService;
import pn.torn.goldeneye.torn.service.faction.attack.contribution.image.RwContributionDocumentAssembler;
import pn.torn.goldeneye.torn.service.faction.attack.contribution.image.RwContributionTextAssembler;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.util.List;

/**
 * RW真赛贡献榜查询策略
 *
 * <p>公开指令，不触发任何抓取或结算行为；按发送者所属帮派统计，
 * 只在本功能开放的5个群内可见。无合格场次时不渲染表格，直接回复兜底文案。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Component
@RequiredArgsConstructor
public class FactionRwContributionStrategyImpl extends BaseRwStrategy {
    private static final String NO_QUALIFIED_WAR_TEXT = "暂无符合条件的真赛（需已结束且对手得分>3000）";

    private final RwContributionQueryService queryService;
    private final RwContributionDocumentAssembler documentAssembler;
    private final RwContributionTextAssembler textAssembler;
    private final TableImageRenderer imageRenderer;
    private final TornSettingFactionManager factionManager;

    @Override
    public String getCommand() {
        return BotCommands.RW_CONTRIBUTION;
    }

    @Override
    public String getCommandDescription() {
        return "统计最近RW真赛成员贡献";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = getTornFactionIdBySender(sender);
        TornSettingFactionDO faction = factionManager.getIdMap().get(factionId);
        if (faction == null) {
            return buildTextMsg(NO_QUALIFIED_WAR_TEXT);
        }

        RwContributionReportBO report = queryService.buildReport(factionId);
        if (report.wars().isEmpty()) {
            return buildTextMsg(NO_QUALIFIED_WAR_TEXT);
        }

        String image = imageRenderer.render(documentAssembler.assemble(faction.getFactionShortName(), report));
        return List.<QqMsgParam<?>>of(ImageQqMsg.fromBase64(image),
                new TextQqMsg(textAssembler.assemble(faction.getFactionShortName(), report)));
    }
}
