package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;
import pn.torn.goldeneye.torn.service.activity.HeatmapImageRenderer;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 活跃度对比指令
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityCompareStrategyImpl extends SmthMsgStrategy {
    private final ActivityHeatmapService heatmapService;
    private static final int DEFAULT_DAYS = 28;

    @Override
    public String getCommand() {
        return BotCommands.ACTIVITY_COMPARE;
    }

    @Override
    public String getCommandDescription() {
        return "对比所在帮派与目标帮派的活跃度";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg) || !NumberUtils.isLong(msg)) {
            return super.buildTextMsg("请提供目标帮派ID，例如: g#" + BotCommands.ACTIVITY_COMPARE + "#12345");
        }

        TornUserDO user = super.getTornUser(sender, "");
        long factionId = user.getFactionId();
        long targetFactionId = Long.parseLong(msg.trim());

        if (factionId == targetFactionId) {
            return super.buildTextMsg("对比自己帮派是准备造反吗");
        }

        ActivityComparisonHeatmapVO heatmap = heatmapService.compareFactions(factionId, targetFactionId, DEFAULT_DAYS);
        if (heatmap.isDataSufficient()) {
            return super.buildImageMsg(HeatmapImageRenderer.renderComparisonAsBase64(heatmap));
        } else {
            return super.buildTextMsg(heatmap.getInsufficientMessage());
        }
    }
}
