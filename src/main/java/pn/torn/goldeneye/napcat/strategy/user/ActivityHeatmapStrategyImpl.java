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
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
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
 * 活跃度热力图指令
 * <p>
 * 支持无日期参数（等价最近 28 天）与{@code 从#yyyy-MM-dd}/{@code 截至#yyyy-MM-dd}日期参数；
 * 日期尾部参数统一由{@link ActivityQueryRangeParser}解析，本类不复制 split 之外的日期逻辑。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.07
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityHeatmapStrategyImpl extends SmthMsgStrategy {
    private final ActivityHeatmapService heatmapService;

    @Override
    public String getCommand() {
        return BotCommands.ACTIVITY_HEATMAP;
    }

    @Override
    public String getCommandDescription() {
        return "查询活跃度热力图，支持帮派/用户和从/截至日期参数";
    }

    /**
     * 仅“用户”模式把 at 目标按 QQ 解析为绑定用户的 Torn userId；
     * “帮派”模式不接受 at 目标，收到 at 标记时按参数错误处理。
     *
     * @return true 表示支持 at 用户目标
     */
    @Override
    public boolean supportsAtUserTarget() {
        return true;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String[] msgArray = msg.split("#");
        // 合法形态：[类型, 目标] 或 [类型, 目标, 范围关键字, 日期]
        if (msgArray.length != 2 && msgArray.length != 4) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }
        String type = msgArray[0].trim();
        // at 标记以 \u0000 为边界，trim 会破坏标记结构，必须基于原始目标片段探测
        String targetText = msgArray[1];

        Optional<ActivityQueryRange> range = ActivityQueryRangeParser.parse(
                tailSegments(msgArray), LocalDate.now(TornActivityCollectService.HEATMAP_ZONE));
        if (range.isEmpty()) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        if (super.hasAtMarker(targetText)) {
            return handleAtTarget(sender, type, targetText, range.get());
        }

        String idText = targetText.trim();
        if (!isValidQuery(type, idText)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        long id = Long.parseLong(idText);
        if ("帮派".equals(type)) {
            return buildFactionHeatmapReply(id, range.get());
        }
        return buildPersonalHeatmapReply(id, range.get());
    }

    /**
     * 提取目标段之后的日期尾部参数段
     *
     * @param msgArray 指令分段数组
     * @return 尾部参数段列表，无日期参数时为空列表
     */
    private static List<String> tailSegments(String[] msgArray) {
        if (msgArray.length <= 2) {
            return List.of();
        }
        return Arrays.asList(msgArray).subList(2, msgArray.length);
    }

    /**
     * 处理携带 at 标记的查询：仅“用户”模式把 at 目标转换为绑定用户的 Torn userId，
     * 其余模式返回参数错误提示，不把 QQ 号当作业务 ID 使用。
     *
     * @param sender     消息发送人
     * @param type       查询类型
     * @param targetText 携带 at 标记的原始目标片段
     * @param range      已解析的查询日期范围
     * @return 回复消息
     */
    private List<? extends QqMsgParam<?>> handleAtTarget(QqRecMsgSender sender, String type,
                                                         String targetText, ActivityQueryRange range) {
        if (!"用户".equals(type)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        TornUserDO user = super.getTornUser(sender, targetText);
        return buildPersonalHeatmapReply(user.getId(), range);
    }

    /**
     * 构建帮派热力图回复
     *
     * @param factionId 帮派 ID
     * @param range     查询日期范围
     * @return 回复消息
     */
    private List<? extends QqMsgParam<?>> buildFactionHeatmapReply(long factionId, ActivityQueryRange range) {
        FactionActivityHeatmapVO heatmap = heatmapService.queryFactionHeatmap(factionId, range);
        if (heatmap.isHasData()) {
            return super.buildImageMsg(HeatmapImageRenderer.renderFactionAsBase64(heatmap));
        }
        return super.buildTextMsg(ActivityHeatmapService.NO_DATA_MESSAGE);
    }

    /**
     * 构建个人热力图回复
     *
     * @param userId 用户 ID
     * @param range  查询日期范围
     * @return 回复消息
     */
    private List<? extends QqMsgParam<?>> buildPersonalHeatmapReply(long userId, ActivityQueryRange range) {
        PersonalActivityHeatmapVO heatmap = heatmapService.queryPersonalHeatmap(userId, range);
        if (heatmap.isHasData()) {
            return super.buildImageMsg(HeatmapImageRenderer.renderPersonalAsBase64(heatmap));
        }
        return super.buildTextMsg(ActivityHeatmapService.NO_DATA_MESSAGE);
    }

    /**
     * 校验热力图查询类型和目标 ID。
     *
     * @param type   查询类型
     * @param idText 目标 ID 文本
     * @return 参数有效时返回 true
     */
    static boolean isValidQuery(String type, String idText) {
        boolean isCommand = "帮派".equals(type) || "用户".equals(type);
        if (!isCommand || !NumberUtils.isLong(idText)) {
            return false;
        }
        try {
            return Long.parseLong(idText) > 0;
        } catch (NumberFormatException e) {
            log.debug("活跃度热力图目标 ID 超出 long 范围: {}", idText);
            return false;
        }
    }

    /**
     * 构建格式介绍消息
     */
    private static String buildFormatIntroMsg() {
        return "查询格式举例如下: " +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#帮派#12345, 查询12345帮派最近28天的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#用户#54321, 查询54321玩家最近28天的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#帮派#12345#从#2026-01-01, 查询2026-01-01至今的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#用户#54321#截至#2026-01-01, 查询截至2026-01-01的最近28天热力图";
    }
}
