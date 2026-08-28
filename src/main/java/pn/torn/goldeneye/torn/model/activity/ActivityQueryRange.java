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
     * 展开范围内的自然日列表（升序）。
     *
     * @return 升序自然日列表
     */
    public List<LocalDate> dates() {
        List<LocalDate> dates = new ArrayList<>(totalDays());
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }
}
