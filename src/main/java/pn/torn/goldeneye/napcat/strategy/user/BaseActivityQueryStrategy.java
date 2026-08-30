package pn.torn.goldeneye.napcat.strategy.user;

import org.springframework.util.StringUtils;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.service.activity.TornActivityCollectService;
import pn.torn.goldeneye.torn.service.activity.query.ActivityQueryRangeParser;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 活跃度查询类指令基类
 * <p>
 * 统一处理参数空判、{@code #}分段数上限与截止日期尾部参数解析，任一环节非法均回复
 * 派生类的格式介绍；派生类通过{@link #dateTailStartIndex(String[])}声明业务段边界，
 * 并在{@link #handleQuery(QqRecMsgSender, String[], ActivityQueryRange)}中实现目标解析与查询分发。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
public abstract class BaseActivityQueryStrategy extends SmthMsgStrategy {
    /**
     * 发送人未加入帮派时的稳定提示文案
     */
    protected static final String NOT_IN_FACTION_MSG = "你还没有加入帮派哦";

    /**
     * 合法指令的最大有效分段数（两个业务段 + 截止日期段）
     */
    private static final int MAX_SEGMENT_COUNT = 3;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String[] msgArray = msg.split("#");
        if (msgArray.length < 1 || msgArray.length > MAX_SEGMENT_COUNT) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        Optional<ActivityQueryRange> range = ActivityQueryRangeParser.parse(
                dateTailSegments(msgArray), LocalDate.now(TornActivityCollectService.HEATMAP_ZONE));
        if (range.isEmpty()) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        return handleQuery(sender, msgArray, range.get());
    }

    /**
     * 解析截止日期尾部参数的起始下标：该下标之前的段均为业务段
     *
     * @param msgArray 指令分段数组
     * @return 截止日期尾部参数起始下标
     */
    protected abstract int dateTailStartIndex(String[] msgArray);

    /**
     * 处理已通过分段与截止日期校验的查询
     *
     * @param sender   消息发送人
     * @param msgArray 指令分段数组
     * @param range    已解析的查询日期范围
     * @return 回复消息
     */
    protected abstract List<? extends QqMsgParam<?>> handleQuery(QqRecMsgSender sender, String[] msgArray,
                                                                 ActivityQueryRange range);

    /**
     * 构建格式介绍消息
     *
     * @return 格式介绍消息
     */
    protected abstract String buildFormatIntroMsg();

    /**
     * 提取业务段之后的截止日期尾部参数段
     *
     * @param msgArray 指令分段数组
     * @return 尾部参数段列表，无截止日期时为空列表
     */
    private List<String> dateTailSegments(String[] msgArray) {
        int fromIndex = dateTailStartIndex(msgArray);
        if (msgArray.length <= fromIndex) {
            return List.of();
        }
        return Arrays.asList(msgArray).subList(fromIndex, msgArray.length);
    }
}
