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
 * 统一处理参数空判、at 标记归位、{@code #}分段数上限与尾部参数解析，任一环节非法均回复
 * 派生类的格式介绍；派生类通过{@link #queryTailStartIndex(String[])}声明业务段边界，
 * 需要不同尾部形态时覆写{@link #resolveRange(List, LocalDate)}，
 * 并在{@link #handleQuery(QqRecMsgSender, String[], ActivityQueryRange)}中实现目标解析与查询分发。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.08.30
 */
public abstract class BaseActivityQueryStrategy extends SmthMsgStrategy {
    /**
     * 发送人未加入帮派时的稳定提示文案
     */
    protected static final String NOT_IN_FACTION_MSG = "你还没有加入帮派哦";

    /**
     * 合法指令的最大有效分段数（两个业务段 + 日期段 + 口径段）
     */
    private static final int MAX_SEGMENT_COUNT = 4;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!StringUtils.hasText(msg)) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        String[] msgArray = splitParam(msg);
        if (msgArray.length < 1 || msgArray.length > MAX_SEGMENT_COUNT) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        Optional<ActivityQueryRange> range = resolveRange(
                queryTailSegments(msgArray), LocalDate.now(TornActivityCollectService.HEATMAP_ZONE));
        if (range.isEmpty()) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }

        return handleQuery(sender, msgArray, range.get());
    }

    /**
     * 解析业务段之后的参数段起始下标：该下标之前的段均为业务段
     *
     * @param msgArray 指令分段数组
     * @return 尾部参数起始下标
     */
    protected abstract int queryTailStartIndex(String[] msgArray);

    /**
     * 解析尾部参数段为查询范围。
     * <p>
     * 缺省按普通热力图的「日期 + 口径」形态解析；对比图只有一种视图，覆写本方法沿用
     * “单个截至日期”的既有形态，新的口径写法因此不会进入对比图。
     *
     * @param tailSegments 业务段之后的参数段列表
     * @param today        {@code Asia/Shanghai} 的今天
     * @return 已解析的查询范围；参数非法时返回空
     */
    protected Optional<ActivityQueryRange> resolveRange(List<String> tailSegments, LocalDate today) {
        return ActivityQueryRangeParser.parse(tailSegments, today);
    }

    /**
     * 处理已通过分段与尾部参数校验的查询
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
     * 提取业务段之后的尾部参数段
     *
     * @param msgArray 指令分段数组
     * @return 尾部参数段列表，无尾部参数时为空列表
     */
    private List<String> queryTailSegments(String[] msgArray) {
        int fromIndex = queryTailStartIndex(msgArray);
        if (msgArray.length <= fromIndex) {
            return List.of();
        }
        return Arrays.asList(msgArray).subList(fromIndex, msgArray.length);
    }

    /**
     * 分段并把分发层追加在末尾的 at 标记还原到目标段位置。
     * <p>
     * 分发层按“业务参数 + at 标记”拼接（{@code BaseMessageHandler#resolveParam}），隐含假设 at 是
     * 参数的最后一个语义段；当 at 之后还有日期或口径时，标记会被拼到尾部参数之后，目标段因此变成
     * 空段或空白段。at 目标固定占用类型段之后的目标段，把标记放回该空段即可，尾部参数顺序不变。
     *
     * @param msg 策略参数
     * @return 归一化后的分段数组
     */
    private static String[] splitParam(String msg) {
        int markerIndex = indexOfAtMarker(msg);
        if (markerIndex < 0) {
            return msg.split("#");
        }
        String marker = msg.substring(markerIndex);
        String[] headSegments = msg.substring(0, markerIndex).split("#");
        if (headSegments.length == 1) {
            // “用户#”：尾随空段被 split 丢弃，目标段需要补回
            return new String[]{headSegments[0], marker};
        }
        if (!headSegments[1].isBlank()) {
            // 数字 ID 与 at 混用等既有非法形态：保持原样交给既有校验拒绝
            return msg.split("#");
        }
        headSegments[1] = marker;
        return headSegments;
    }
}
