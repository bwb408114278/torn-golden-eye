package pn.torn.goldeneye.torn.model.activity;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 活跃度热力图不可变查询日期范围
 * <p>
 * 起止日期均为{@code Asia/Shanghai}自然日闭区间；合法性校验由
 * {@code ActivityQueryRangeParser}完成，本 record 只承载结果。
 *
 * @param startDate 起始日期（含）
 * @param endDate   结束日期（含），不晚于今天
 * @param mode      范围解析模式
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
public record ActivityQueryRange(
        LocalDate startDate,
        LocalDate endDate,
        ActivityQueryRangeModeEnum mode) {

    /**
     * 计算范围内自然日总数（闭区间）。
     *
     * @return 自然日数量，最小为 1
     */
    public int totalDays() {
        return (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /**
     * 计算查询范围与 Redis 最近 30 个自然日窗口的交集。
     *
     * @param today 当前 Asia/Shanghai 自然日
     * @return 交集日期，升序；无交集时为空
     */
    public List<LocalDate> redisWindowDates(LocalDate today) {
        LocalDate windowStart = today.minusDays(29);
        LocalDate intersectionStart = startDate.isAfter(windowStart) ? startDate : windowStart;
        LocalDate intersectionEnd = endDate.isBefore(today) ? endDate : today;
        if (intersectionStart.isAfter(intersectionEnd)) {
            return List.of();
        }
        int days = (int) ChronoUnit.DAYS.between(intersectionStart, intersectionEnd) + 1;
        List<LocalDate> dates = new ArrayList<>(days);
        for (LocalDate date = intersectionStart; !date.isAfter(intersectionEnd); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }
}
