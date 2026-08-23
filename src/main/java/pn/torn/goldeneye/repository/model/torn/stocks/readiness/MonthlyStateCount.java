package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

import java.time.LocalDate;

/**
 * 月度状态分组计数。
 *
 * @param effectiveMonth 生效月份（当月1日）
 * @param stateStatus    状态（DRAFT/CONFIRMED/RETIRED等）
 * @param manualOverride 是否人工覆盖
 * @param count          数量
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record MonthlyStateCount(
        LocalDate effectiveMonth,
        String stateStatus,
        boolean manualOverride,
        long count) {
}
