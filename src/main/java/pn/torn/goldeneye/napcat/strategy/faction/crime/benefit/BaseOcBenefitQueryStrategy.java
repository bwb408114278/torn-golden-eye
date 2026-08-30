package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import org.springframework.util.StringUtils;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * OC收益查询类指令基类
 *
 * <p>统一处理{@code #}分段数上限与月份尾段参数解析：末段为严格{@code yyyy-MM}年月时识别为查询
 * 月份；单段时按内容消歧，年月查发送者自己，其余按派生类的目标段语义处理；月份晚于当月、
 * 两段但末段非年月或段数超限时回复派生类的格式介绍。派生类在
 * {@link #handleQuery(QqRecMsgSender, String, YearMonth)}中实现目标段解析与查询分发。</p>
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
public abstract class BaseOcBenefitQueryStrategy extends SmthMsgStrategy {
    /**
     * 合法指令的最大分段数（目标段 + 月份尾段）
     */
    private static final int MAX_SEGMENT_COUNT = 2;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        OcMonthParam param = parseMonthParam(msg, YearMonth.now());
        if (param == null) {
            return super.buildTextMsg(buildFormatIntroMsg());
        }
        return handleQuery(sender, param.targetText(), param.month());
    }

    /**
     * 处理已通过分段与月份校验的查询。
     *
     * @param sender     消息发送人
     * @param targetText 用户目标段文本；空串表示查询发送者自己
     * @param month      查询年月
     * @return 回复消息
     */
    protected abstract List<? extends QqMsgParam<?>> handleQuery(QqRecMsgSender sender, String targetText,
                                                                 YearMonth month);

    /**
     * 构建格式介绍消息。
     *
     * @return 格式介绍消息
     */
    protected abstract String buildFormatIntroMsg();

    /**
     * 构建月份展示文案：当月显示"M月"，历史月显示"yyyy年M月"。
     *
     * @param month 查询年月
     * @return 月份文案
     */
    protected static String monthLabel(YearMonth month) {
        if (YearMonth.now().equals(month)) {
            return month.getMonthValue() + "月";
        }
        return month.getYear() + "年" + month.getMonthValue() + "月";
    }

    /**
     * 解析指令参数为目标段与查询月份。
     *
     * <p>无参数查自己当月；单段按内容消歧：严格{@code yyyy-MM}为月份（查自己），其余为目标段
     * （当月）；两段时首段为目标段、末段必须是合法且不晚于当月的年月。月份晚于当月、两段末段
     * 非年月或段数超限均返回{@code null}，由调用方回复格式介绍。{@code String#split}会丢弃
     * 尾部空段，因此尾部多余的{@code #}被容忍并按其前面的段解析。</p>
     *
     * @param msg          指令参数文本
     * @param currentMonth 当前年月
     * @return 解析结果；非法时返回{@code null}
     */
    static OcMonthParam parseMonthParam(String msg, YearMonth currentMonth) {
        if (!StringUtils.hasText(msg)) {
            return new OcMonthParam("", currentMonth);
        }

        String[] msgArray = msg.split("#");
        if (msgArray.length > MAX_SEGMENT_COUNT) {
            return null;
        }

        String lastSegment = msgArray[msgArray.length - 1];
        YearMonth month = parseStrictMonth(lastSegment);
        if (msgArray.length == MAX_SEGMENT_COUNT) {
            if (month == null || month.isAfter(currentMonth)) {
                return null;
            }
            return new OcMonthParam(msgArray[0], month);
        }

        if (month == null) {
            return new OcMonthParam(lastSegment, currentMonth);
        }
        if (month.isAfter(currentMonth)) {
            return null;
        }
        return new OcMonthParam("", month);
    }

    /**
     * 严格解析yyyy-MM年月，任何格式偏差（含空白、单数字月份、时间部分）返回null。
     *
     * @param text 年月文本
     * @return 解析结果，非法时为null
     */
    private static YearMonth parseStrictMonth(String text) {
        if (text == null) {
            return null;
        }
        try {
            return YearMonth.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * OC收益指令月份参数解析结果。
     *
     * @param targetText 用户目标段文本；空串表示查询发送者自己
     * @param month      查询年月
     */
    protected record OcMonthParam(String targetText, YearMonth month) {
    }
}
