package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import org.springframework.util.StringUtils;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
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
 * 两段但末段非年月或段数超限时回复派生类的格式介绍。分发层会把内部at标记追加在参数末尾，
 * 解析时先剥离该标记再识别月份，at目标不与ID目标段混用。派生类在
 * {@link #handleQuery(QqRecMsgSender, String, YearMonth)}中实现目标段解析与查询分发。</p>
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
public abstract class BaseOcBenefitQueryStrategy extends SmthMsgStrategy {

    /**
     * OC收益查询支持通过 at 指定收益查询目标用户。
     *
     * @return true 表示支持 at 用户目标
     */
    @Override
    public boolean supportsAtUserTarget() {
        return true;
    }

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
     * （当月）；两段时首段为目标段、末段必须是合法且不晚于当月的年月。参数末尾的分发层at标记
     * 先剥离，剥离后余下必须为空或单个月份，at目标不与ID目标段混用。月份晚于当月、末段非年月
     * 或段数超限均返回{@code null}，由调用方回复格式介绍。{@code String#split}会丢弃尾部空段，
     * 因此尾部多余的{@code #}被容忍并按其前面的段解析。</p>
     *
     * @param msg          指令参数文本
     * @param currentMonth 当前年月
     * @return 解析结果；非法时返回{@code null}
     */
    static OcMonthParam parseMonthParam(String msg, YearMonth currentMonth) {
        if (!StringUtils.hasText(msg)) {
            return new OcMonthParam("", currentMonth);
        }

        String atMarker = extractTrailingAtMarker(msg);
        if (!atMarker.isEmpty()) {
            return parseAtTargetParam(msg.substring(0, msg.length() - atMarker.length()),
                    atMarker, currentMonth);
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
     * 解析at目标与可选月份。
     *
     * <p>at标记必须独占目标语义：剥离标记后余下参数为空表示当月，为单个合法年月表示历史月；
     * 兼容QQ客户端在at前后保留指令分隔符的形态（例如{@code " #2026-07"}）。其余形态
     * （ID目标段、多段、非法年月、未来月）一律拒绝。</p>
     *
     * @param remainder    剥离at标记后的余下参数
     * @param atMarker     内部at标记
     * @param currentMonth 当前年月
     * @return 解析结果；非法时返回{@code null}
     */
    private static OcMonthParam parseAtTargetParam(String remainder, String atMarker, YearMonth currentMonth) {
        String normalizedRemainder = remainder.trim();
        if (normalizedRemainder.startsWith("#")) {
            normalizedRemainder = normalizedRemainder.substring(1).trim();
        }
        if (normalizedRemainder.isEmpty()) {
            return new OcMonthParam(atMarker, currentMonth);
        }
        String[] msgArray = normalizedRemainder.split("#");
        if (msgArray.length > MAX_SEGMENT_COUNT - 1) {
            return null;
        }
        YearMonth month = parseStrictMonth(normalizedRemainder);
        if (month == null || month.isAfter(currentMonth)) {
            return null;
        }
        return new OcMonthParam(atMarker, month);
    }

    /**
     * 提取分发层追加在参数末尾的内部at标记（含非法at标记）。
     *
     * <p>标记以控制字符为边界且只能由解析层生成，普通文本无法伪造；取首个标记起点到参数末尾
     * 的整段作为标记，标记结构合法性仍由{@code getTornUser}统一校验。</p>
     *
     * @param msg 指令参数文本
     * @return 末尾at标记；无标记时为空串
     */
    private static String extractTrailingAtMarker(String msg) {
        int markerIndex = indexOfAtMarker(msg);
        return markerIndex < 0 ? "" : msg.substring(markerIndex);
    }

    /**
     * 定位参数中首个内部at标记（含非法at标记）的起始下标。
     *
     * @param msg 指令参数文本
     * @return 首个标记起始下标；无标记时为-1
     */
    private static int indexOfAtMarker(String msg) {
        int atIndex = msg.indexOf(QqCommandMessage.AT_MARKER_PREFIX);
        int invalidIndex = msg.indexOf(QqCommandMessage.INVALID_AT_MARKER);
        if (atIndex < 0) {
            return invalidIndex;
        }
        if (invalidIndex < 0) {
            return atIndex;
        }
        return Math.min(atIndex, invalidIndex);
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
     * @param targetText 目标段文本；空串表示无目标段（派生类按缺省目标处理），at目标时为内部at标记
     * @param month      查询年月
     */
    protected record OcMonthParam(String targetText, YearMonth month) {
    }
}
