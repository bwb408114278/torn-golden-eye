package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;
import pn.torn.goldeneye.torn.service.activity.HeatmapImageRenderer;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 活跃度热力图指令
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.07
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityHeatmapStrategyImpl extends SmthMsgStrategy {
    private final ActivityHeatmapService heatmapService;

    private static final int DEFAULT_DAYS = 28;

    @Override
    public String getCommand() {
        return BotCommands.ACTIVITY_HEATMAP;
    }

    @Override
    public String getCommandDescription() {
        return "查询活跃度热力图，支持帮派/用户";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String[] msgArray = msg.split("#");
        boolean isCommand = "帮派".equals(msgArray[0]) || "用户".equals(msgArray[0]);
        if (msgArray.length < 2 || !isCommand || !NumberUtils.isLong(msgArray[1])) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String type = msgArray[0];
        long id = Long.parseLong(msgArray[1]);
        if ("帮派".equals(type)) {
            FactionActivityHeatmapVO heatmap = heatmapService.queryFactionHeatmap(id, DEFAULT_DAYS);
            if (heatmap.isDataSufficient()) {
                return super.buildImageMsg(HeatmapImageRenderer.renderFactionAsBase64(heatmap));
            } else {
                return super.buildTextMsg(heatmap.getInsufficientMessage());
            }
        } else {
            PersonalActivityHeatmapVO heatmap = heatmapService.queryPersonalHeatmap(id, DEFAULT_DAYS);
            if (heatmap.isDataSufficient()) {
                return super.buildImageMsg(HeatmapImageRenderer.renderPersonalAsBase64(heatmap));
            } else {
                return super.buildTextMsg(heatmap.getInsufficientMessage());
            }
        }
    }

    /**
     * 构建格式介绍消息
     */
    private static String buildFormatIntroMsg() {
        return "查询格式举例如下: " +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#帮派#12345, 查询12345帮派的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#用户#54321, 查询54321玩家的热力图";
    }
}
