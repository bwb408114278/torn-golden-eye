package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;
import pn.torn.goldeneye.torn.service.activity.HeatmapImageRenderer;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 活跃度热力图指令
 * <p>
 * 合法形态：{@code [类型]}、{@code [类型, 目标|截止日期]}、{@code [类型, 目标, 截止日期]}；
 * 目标缺省时“用户”模式查询发送人绑定用户自己、“帮派”模式查询其所属帮派。
 * 参数空判、分段数上限与截止日期解析由{@link BaseActivityQueryStrategy}统一处理，
 * 本类只保留目标段解析与查询分发。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.07.07
 */
@Component
@RequiredArgsConstructor
public class ActivityHeatmapStrategyImpl extends BaseActivityQueryStrategy {
    private final ActivityHeatmapService heatmapService;

    @Override
    public String getCommand() {
        return BotCommands.ACTIVITY_HEATMAP;
    }

    @Override
    public String getCommandDescription() {
        return "查询活跃度热力图，支持帮派/用户（缺省查自己/所属帮派）和截止日期参数";
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

    /**
     * 第二段为纯数字 ID 或 at 标记时业务段为类型与目标两段，否则仅类型一段
     *
     * @param msgArray 指令分段数组
     * @return 截止日期尾部参数起始下标
     */
    @Override
    protected int dateTailStartIndex(String[] msgArray) {
        return resolveTargetText(msgArray) != null ? 2 : 1;
    }

    @Override
    protected List<? extends QqMsgParam<?>> handleQuery(QqRecMsgSender sender, String[] msgArray,
                                                        ActivityQueryRange range) {
        String type = msgArray[0].trim();
        if (!"帮派".equals(type) && !"用户".equals(type)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String targetText = resolveTargetText(msgArray);
        if (targetText == null) {
            return handleNoTarget(sender, type, range);
        }
        if (super.hasAtMarker(targetText)) {
            return handleAtTarget(sender, type, targetText, range);
        }
        if (!isValidTargetId(targetText)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        long id = Long.parseLong(targetText.trim());
        if ("帮派".equals(type)) {
            return buildFactionHeatmapReply(id, range);
        }
        return buildPersonalHeatmapReply(id, range);
    }

    /**
     * 解析目标段：第二段为纯数字 ID 或 at 标记时视为目标；否则视为截止日期段，目标缺省
     *
     * @param msgArray 指令分段数组
     * @return 目标段文本；目标缺省时返回 null
     */
    private String resolveTargetText(String[] msgArray) {
        if (msgArray.length < 2) {
            return null;
        }
        // at 标记以 \u0000 为边界，trim 会破坏标记结构，必须基于原始片段探测
        String segment = msgArray[1];
        if (super.hasAtMarker(segment)) {
            return segment;
        }
        String trimmed = segment.trim();
        return NumberUtils.isLong(trimmed) ? trimmed : null;
    }

    /**
     * 处理目标缺省查询：“用户”模式查询发送人绑定用户自己，“帮派”模式查询其所属帮派
     *
     * @param sender 消息发送人
     * @param type   查询类型
     * @param range  已解析的查询日期范围
     * @return 回复消息
     */
    private List<? extends QqMsgParam<?>> handleNoTarget(QqRecMsgSender sender, String type,
                                                         ActivityQueryRange range) {
        if ("用户".equals(type)) {
            TornUserDO user = super.getTornUser(sender, "");
            return buildPersonalHeatmapReply(user.getId(), range);
        }

        long factionId = super.getTornFactionIdBySender(sender);
        if (factionId <= 0) {
            return super.buildTextMsg(NOT_IN_FACTION_MSG);
        }
        return buildFactionHeatmapReply(factionId, range);
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
     * 校验目标 ID 为正整数
     *
     * @param idText 目标 ID 文本
     * @return 参数有效时返回 true
     */
    static boolean isValidTargetId(String idText) {
        String trimmed = idText.trim();
        return NumberUtils.isLong(trimmed) && Long.parseLong(trimmed) > 0;
    }

    /**
     * 构建格式介绍消息
     *
     * @return 格式介绍消息
     */
    @Override
    protected String buildFormatIntroMsg() {
        return "查询格式举例如下: " +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#用户, 查询自己最近28天的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#帮派, 查询自己帮派最近28天的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#帮派#12345, 查询12345帮派最近28天的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#用户#54321, 查询54321玩家最近28天的热力图" +
                "\ng#" + BotCommands.ACTIVITY_HEATMAP + "#帮派#12345#2026-01-01, 查询截至2026-01-01的最近28天热力图";
    }
}
