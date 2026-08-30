package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
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
 * 帮派 A 为第一个目标帮派，仅给出单个帮派时为发送人绑定 Torn 用户所在帮派，帮派 B 为
 * 其后帮派；支持无日期参数与单个截止日期尾部参数，日期尾部参数统一由
 * {@link ActivityQueryRangeParser}解析。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.07.08
 */
@Component
@RequiredArgsConstructor
public class ActivityCompareStrategyImpl extends SmthMsgStrategy {
    /**
     * 发送人未加入帮派时的稳定提示文案
     */
    private static final String NOT_IN_FACTION_MSG = "你还没有加入帮派哦";

    /**
     * 对比目标与自己帮派相同时的稳定提示文案
     */
    private static final String SELF_COMPARE_MSG = "对比自己帮派是准备造反吗";

    private final ActivityHeatmapService heatmapService;

    @Override
    public String getCommand() {
        return BotCommands.ACTIVITY_COMPARE;
    }

    @Override
    public String getCommandDescription() {
        return "对比两个帮派（默认为所在帮派）的活跃度，支持截止日期参数";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String[] msgArray = msg.split("#");
        // 合法形态：[目标帮派ID]、[目标帮派ID, 截止日期]、[帮派AID, 帮派BID]、[帮派AID, 帮派BID, 截止日期]
        if (msgArray.length < 1 || msgArray.length > 3) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }
        String firstText = msgArray[0].trim();
        if (!NumberUtils.isLong(firstText)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        boolean hasFactionA = msgArray.length >= 2 && NumberUtils.isLong(msgArray[1].trim());
        Optional<ActivityQueryRange> range = ActivityQueryRangeParser.parse(
                tailSegments(msgArray, hasFactionA), LocalDate.now(TornActivityCollectService.HEATMAP_ZONE));
        if (range.isEmpty()) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        long firstId = Long.parseLong(firstText);
        long factionAId;
        long factionBId;
        if (hasFactionA) {
            factionAId = firstId;
            factionBId = Long.parseLong(msgArray[1].trim());
        } else {
            factionAId = super.getTornFactionIdBySender(sender);
            if (factionAId <= 0) {
                return super.buildTextMsg(NOT_IN_FACTION_MSG);
            }
            factionBId = firstId;
        }

        if (factionAId == factionBId) {
            return super.buildTextMsg(SELF_COMPARE_MSG);
        }

        ActivityComparisonHeatmapVO heatmap = heatmapService.compareFactions(factionAId, factionBId, range.get());
        if (heatmap.isHasData()) {
            return super.buildImageMsg(HeatmapImageRenderer.renderComparisonAsBase64(heatmap));
        }
        return super.buildTextMsg(ActivityHeatmapService.NO_DATA_MESSAGE);
    }

    /**
     * 提取帮派段（如存在双帮派段）之后的日期尾部参数段
     *
     * @param msgArray    指令分段数组
     * @param hasFactionA 是否存在第二个帮派段
     * @return 尾部参数段列表，无截止日期时为空列表
     */
    private static List<String> tailSegments(String[] msgArray, boolean hasFactionA) {
        int fromIndex = hasFactionA ? 2 : 1;
        if (msgArray.length <= fromIndex) {
            return List.of();
        }
        return Arrays.asList(msgArray).subList(fromIndex, msgArray.length);
    }

    /**
     * 构建格式介绍消息
     */
    private static String buildFormatIntroMsg() {
        return "查询格式举例如下: " +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#12345, 对比所在帮派与12345帮派最近28天的活跃度" +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#111#222, 对比111与222帮派最近28天的活跃度" +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#111#222#2026-01-01, 对比截至2026-01-01的最近28天活跃度";
    }
}
