package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;
import pn.torn.goldeneye.torn.service.activity.HeatmapImageRenderer;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 活跃度对比指令
 * <p>
 * 帮派 A 为第一个目标帮派，仅给出单个帮派时为发送人绑定 Torn 用户所在帮派，帮派 B 为
 * 其后帮派；参数空判、分段数上限与截止日期尾部参数解析由{@link BaseActivityQueryStrategy}统一处理。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.07.08
 */
@Component
@RequiredArgsConstructor
public class ActivityCompareStrategyImpl extends BaseActivityQueryStrategy {
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

    /**
     * 第二段为纯数字帮派 ID 时业务段为两个帮派，否则仅目标帮派一段
     *
     * @param msgArray 指令分段数组
     * @return 截止日期尾部参数起始下标
     */
    @Override
    protected int dateTailStartIndex(String[] msgArray) {
        return hasTwoFactions(msgArray) ? 2 : 1;
    }

    @Override
    protected List<? extends QqMsgParam<?>> handleQuery(QqRecMsgSender sender, String[] msgArray,
                                                        ActivityQueryRange range) {
        String firstText = msgArray[0].trim();
        if (!NumberUtils.isLong(firstText)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        long firstId = Long.parseLong(firstText);
        long factionAId;
        long factionBId;
        if (hasTwoFactions(msgArray)) {
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

        ActivityComparisonHeatmapVO heatmap = heatmapService.compareFactions(factionAId, factionBId, range);
        if (heatmap.isHasData()) {
            return super.buildImageMsg(HeatmapImageRenderer.renderComparisonAsBase64(heatmap));
        }
        return super.buildTextMsg(ActivityHeatmapService.NO_DATA_MESSAGE);
    }

    /**
     * 判断是否存在第二个帮派段：第二段为纯数字 ID 时为双帮派形态，否则第二段属于截止日期尾部参数
     *
     * @param msgArray 指令分段数组
     * @return 存在第二个帮派段时返回 true
     */
    private static boolean hasTwoFactions(String[] msgArray) {
        return msgArray.length >= 2 && NumberUtils.isLong(msgArray[1].trim());
    }

    /**
     * 构建格式介绍消息
     *
     * @return 格式介绍消息
     */
    @Override
    protected String buildFormatIntroMsg() {
        return "查询格式举例如下: " +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#12345, 对比所在帮派与12345帮派最近28天的活跃度" +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#111#222, 对比111与222帮派最近28天的活跃度" +
                "\ng#" + BotCommands.ACTIVITY_COMPARE + "#111#222#2026-01-01, 对比截至2026-01-01的最近28天活跃度";
    }
}
