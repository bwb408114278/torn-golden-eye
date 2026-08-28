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
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;
import pn.torn.goldeneye.torn.service.activity.HeatmapImageRenderer;
import pn.torn.goldeneye.torn.service.activity.TornActivityCollectService;
import pn.torn.goldeneye.torn.service.activity.query.ActivityQueryRangeParser;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 活跃度对比指令
 * <p>
 * 帮派 A 为发送人绑定 Torn 用户所在帮派，帮派 B 为目标帮派；
 * 支持无日期参数与{@code 从}/{@code 截至}日期参数，日期尾部参数统一由
 * {@link ActivityQueryRangeParser}解析。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityCompareStrategyImpl extends SmthMsgStrategy {
    private final ActivityHeatmapService heatmapService;

    @Override
    public String getCommand() {
        return BotCommands.ACTIVITY_COMPARE;
    }

    @Override
    public String getCommandDescription() {
        return "对比所在帮派与目标帮派的活跃度，支持从/截至日期参数";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String[] msgArray = msg.split("#");
        // 合法形态：[目标帮派ID] 或 [目标帮派ID, 范围关键字, 日期]
        if (msgArray.length != 1 && msgArray.length != 3) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }
        String targetText = msgArray[0].trim();
        if (!NumberUtils.isLong(targetText)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        Optional<ActivityQueryRange> range = ActivityQueryRangeParser.parse(
                tailSegments(msgArray), LocalDate.now(TornActivityCollectService.HEATMAP_ZONE));
        if (range.isEmpty()) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        TornUserDO user = super.getTornUser(sender, "");
        long factionId = user.getFactionId();
        long targetFactionId = Long.parseLong(targetText);

        if (factionId == targetFactionId) {
            return super.buildTextMsg("对比自己帮派是准备造反吗");
        }

        ActivityComparisonHeatmapVO heatmap = heatmapService.compareFactions(factionId, targetFactionId, range.get());
        if (heatmap.isHasData()) {
            return super.buildImageMsg(HeatmapImageRenderer.renderComparisonAsBase64(heatmap));
        }
        return super.buildTextMsg(ActivityHeatmapService.NO_DATA_MESSAGE);
    }

    /**
     * 提取目标段之后的日期尾部参数段
     *
     * @param msgArray 指令分段数组
     * @return 尾部参数段列表，无日期参数时为空列表
     */
    private static List<String> tailSegments(String[] msgArray) {
        if (msgArray.length <= 1) {
            return List.of();
        }
        return Arrays.asList(msgArray).subList(1, msgArray.length);
    }

    /**
     * 构建格式介绍消息
     */
    private static String buildFormatIntroMsg() {
        return "查询格式举例如下: " +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#12345, 对比所在帮派与12345帮派最近28天的活跃度" +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#12345#从#2026-01-01, 对比2026-01-01至今的活跃度" +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#12345#截至#2026-01-01, 对比截至2026-01-01的最近28天活跃度";
    }
}
