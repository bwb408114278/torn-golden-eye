package pn.torn.goldeneye.torn.service.activity.query;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.model.activity.ActivityCaliberEnum;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 活跃度热力图尾部参数解析纯函数
 * <p>
 * 普通热力图与对比图两个 Bot Strategy 唯一允许的尾部参数解析点。普通形态接受「日期」与「口径」
 * 各至多一段，按内容形态判定，与书写顺序无关；对比图只有最近 28 天一种视图，用户用截至日期
 * 选窗口末端，因此单独提供不接受口径的解析入口。日期严格为{@code yyyy-MM-dd}（ISO_LOCAL_DATE），
 * 不接受时间、时区、Epoch、相对日期或空白；锚点日期不得晚于今天。窗口一律为
 * {@code [锚点 - (窗口天数 - 1), 锚点]}，推导只写一份。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.08.28
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ActivityQueryRangeParser {

    /**
     * 普通热力图尾部允许的最大参数段数（日期与口径各一段）
     */
    static final int MAX_TAIL_SEGMENT_COUNT = 2;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * 解析普通热力图的尾部参数段。
     * <p>
     * 缺省规则：完全无参数为典型周近 28 天；只给日期为该日单日；只给口径以今天为锚点；
     * 锚点晚于今天、同类参数重复、超过两段或出现未定义关键字一律返回空。
     *
     * @param tailSegments 业务段之后的参数段列表（可为 null 或空）
     * @param today        {@code Asia/Shanghai} 的今天
     * @return 合法时返回查询范围；参数非法时返回空
     */
    public static Optional<ActivityQueryRange> parse(List<String> tailSegments, LocalDate today) {
        if (tailSegments == null || tailSegments.isEmpty()) {
            return Optional.of(rangeOf(ActivityCaliberEnum.TYPICAL_WEEK, today));
        }
        if (tailSegments.size() > MAX_TAIL_SEGMENT_COUNT) {
            return Optional.empty();
        }

        Optional<ActivityCaliberEnum> caliber = Optional.empty();
        Optional<LocalDate> anchorDate = Optional.empty();
        for (String segment : tailSegments) {
            Optional<ActivityCaliberEnum> matchedCaliber = ActivityCaliberEnum.fromKeyword(segment);
            if (matchedCaliber.isPresent()) {
                if (caliber.isPresent()) {
                    return Optional.empty();
                }
                caliber = matchedCaliber;
                continue;
            }
            Optional<LocalDate> parsedDate = parseStrictDate(segment);
            if (parsedDate.isEmpty() || anchorDate.isPresent()) {
                return Optional.empty();
            }
            anchorDate = parsedDate;
        }

        if (anchorDate.isEmpty()) {
            // 没有日期段时每段都必须是口径段，因此只给口径即以今天为锚点
            return caliber.map(value -> rangeOf(value, today));
        }
        return resolveWindow(caliber, anchorDate.orElseThrow(), today);
    }

    /**
     * 解析对比图的尾部参数段。
     * <p>
     * 对比图只有最近 28 天一种视图，用户用截至日期选窗口末端，因此接受 0 或 1 个截至日期；
     * 出现口径关键字、多段或非法日期返回空，新的「日期 + 口径」形态不会把口径引入对比图。
     *
     * @param tailSegments 业务段之后的参数段列表（可为 null 或空）
     * @param today        {@code Asia/Shanghai} 的今天
     * @return 合法时返回以截至日期为锚点的 28 天范围；参数非法时返回空
     */
    public static Optional<ActivityQueryRange> parseUntilDate(List<String> tailSegments, LocalDate today) {
        if (tailSegments == null || tailSegments.isEmpty()) {
            return Optional.of(rangeOf(ActivityCaliberEnum.TYPICAL_WEEK, today));
        }
        if (tailSegments.size() != 1) {
            return Optional.empty();
        }

        String segment = tailSegments.getFirst();
        if (ActivityCaliberEnum.fromKeyword(segment).isPresent()) {
            return Optional.empty();
        }
        return parseStrictDate(segment)
                .filter(anchorDate -> !anchorDate.isAfter(today))
                .map(anchorDate -> rangeOf(ActivityCaliberEnum.TYPICAL_WEEK, anchorDate));
    }

    /**
     * 组合已解析出的日期与口径：口径缺省为该日单日，锚点不得晚于今天。
     *
     * @param caliber    已命中的口径；未命中时为空
     * @param anchorDate 锚点日期（窗口最后一天）
     * @param today      {@code Asia/Shanghai} 的今天
     * @return 合法时返回查询范围；锚点为未来时返回空
     */
    private static Optional<ActivityQueryRange> resolveWindow(Optional<ActivityCaliberEnum> caliber,
                                                              LocalDate anchorDate, LocalDate today) {
        if (anchorDate.isAfter(today)) {
            return Optional.empty();
        }
        return Optional.of(rangeOf(caliber.orElse(ActivityCaliberEnum.SINGLE_DAY), anchorDate));
    }

    /**
     * 按口径与锚点日期推导窗口，锚点日期是窗口的最后一天。
     *
     * @param caliber    统计口径
     * @param anchorDate 锚点日期（窗口最后一天）
     * @return 查询范围
     */
    private static ActivityQueryRange rangeOf(ActivityCaliberEnum caliber, LocalDate anchorDate) {
        return new ActivityQueryRange(
                anchorDate.minusDays(caliber.windowDays() - 1L), anchorDate, caliber);
    }

    /**
     * 严格解析 yyyy-MM-dd 日期，任何格式偏差（含空白、时间、时区）返回空
     *
     * @param text 日期文本
     * @return 解析结果，非法时返回空
     */
    private static Optional<LocalDate> parseStrictDate(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text, DATE_FMT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
