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
 * 空列表解析为最近 28 天（DEFAULT）；单个截止日期段解析为以该日为结束日的
 * 28 天锚定范围（UNTIL），不支持起始日期与时间部分。
 * 日期严格为{@code yyyy-MM-dd}（ISO_LOCAL_DATE），不接受时间、时区、Epoch、相对日期或空白；
 * 截止日期不得晚于今天，未来日期拒绝。段数不符或日期非法均返回空。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.28
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ActivityQueryRangeParser {

    /**
     * 无日期参数与截止日期锚定的默认跨度（自然日，闭区间）
     */
    static final int DEFAULT_RANGE_DAYS = 28;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * 解析指令尾部参数段为查询范围。
     *
     * @param tailSegments 目标段之后的参数段列表（可为 null 或空）
     * @param today        {@code Asia/Shanghai} 的今天
     * @return 合法时返回查询范围；段数不符、日期非法或为未来时返回空
     */
    public static Optional<ActivityQueryRange> parse(List<String> tailSegments, LocalDate today) {
        if (tailSegments == null || tailSegments.isEmpty()) {
            return Optional.of(new ActivityQueryRange(
                    today.minusDays(DEFAULT_RANGE_DAYS - 1L), today, ActivityQueryRangeModeEnum.DEFAULT));
        }
        if (tailSegments.size() != 1) {
            return Optional.empty();
        }

        LocalDate endDate = parseStrictDate(tailSegments.get(0));
        if (endDate == null || endDate.isAfter(today)) {
            return Optional.empty();
        }
        return Optional.of(new ActivityQueryRange(
                endDate.minusDays(DEFAULT_RANGE_DAYS - 1L), endDate, ActivityQueryRangeModeEnum.UNTIL));
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
