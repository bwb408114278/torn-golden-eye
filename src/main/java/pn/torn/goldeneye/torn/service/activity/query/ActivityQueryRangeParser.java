package pn.torn.goldeneye.torn.service.activity.query;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRangeModeEnum;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 活跃度热力图日期参数解析纯函数
 * <p>
 * 两个 Bot Strategy 唯一允许的日期参数解析点。输入为指令中目标段之后的尾部参数段：
 * 空列表解析为最近 28 天（DEFAULT）；{@code [从|截至, yyyy-MM-dd]}解析为对应锚定范围。
 * 日期严格为{@code yyyy-MM-dd}（ISO_LOCAL_DATE），不接受时间、时区、Epoch、相对日期或空白；
 * 起始/结束日期不得晚于今天，未来日期拒绝。关键字错误、重复出现、参数段数不符均返回空。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ActivityQueryRangeParser {

    /**
     * 无日期参数与"截至"锚定的默认跨度（自然日，闭区间）
     */
    static final int DEFAULT_RANGE_DAYS = 28;

    /**
     * 范围关键字：从
     */
    static final String KEYWORD_FROM = "从";

    /**
     * 范围关键字：截至
     */
    static final String KEYWORD_UNTIL = "截至";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * 解析指令尾部参数段为查询范围。
     *
     * @param tailSegments 目标段之后的参数段列表（可为 null 或空）
     * @param today        {@code Asia/Shanghai} 的今天
     * @return 合法时返回查询范围；参数段数不符、关键字错误、日期非法或为未来时返回空
     */
    public static Optional<ActivityQueryRange> parse(List<String> tailSegments, LocalDate today) {
        if (tailSegments == null || tailSegments.isEmpty()) {
            return Optional.of(new ActivityQueryRange(
                    today.minusDays(DEFAULT_RANGE_DAYS - 1L), today, ActivityQueryRangeModeEnum.DEFAULT));
        }
        if (tailSegments.size() != 2) {
            return Optional.empty();
        }

        String keyword = tailSegments.get(0) == null ? "" : tailSegments.get(0).trim();
        LocalDate parsedDate = parseStrictDate(tailSegments.get(1));
        if (parsedDate == null || parsedDate.isAfter(today)) {
            return Optional.empty();
        }

        if (KEYWORD_FROM.equals(keyword)) {
            return Optional.of(new ActivityQueryRange(parsedDate, today, ActivityQueryRangeModeEnum.FROM));
        }
        if (KEYWORD_UNTIL.equals(keyword)) {
            return Optional.of(new ActivityQueryRange(
                    parsedDate.minusDays(DEFAULT_RANGE_DAYS - 1L), parsedDate, ActivityQueryRangeModeEnum.UNTIL));
        }
        return Optional.empty();
    }

    /**
     * 严格解析 yyyy-MM-dd 日期，任何格式偏差（含空白、时间、时区）返回 null
     *
     * @param text 日期文本
     * @return 解析结果，非法时返回 null
     */
    private static LocalDate parseStrictDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text, DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
