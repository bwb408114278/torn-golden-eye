package pn.torn.goldeneye.torn.service.stocks.rebuild;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMonthlyStateInitService;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史月度状态范围重算门面。
 * <p>
 * 按 {@code effectiveMonth ASC} 逐月处理与派生数据重建范围相关的月份：
 * 先为缺失股票创建 DRAFT，再重算非人工覆盖 DRAFT，最后按既有冻结条件自动确认 SYSTEM。
 * 每个月独立短事务提交，任一月份失败立即停止后续月份，已完成月份可按相同范围幂等重跑。
 * <p>
 * 本服务不触发轮次交易事务、信号、batch、Shadow、通知、槽位或开关写入。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMonthlyStateRangeRebuildService {

    private final StockMonthlyStateInitService monthlyStateInitService;

    /**
     * 重建指定数据范围内的所有相关月份状态。
     *
     * @param startInclusive 数据范围起始时间（含）
     * @param endExclusive   数据范围结束时间（不含）
     * @return 本次新建/重算/自动确认的月度状态记录总数（仅用于日志观测）
     */
    public int rebuild(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        if (startInclusive == null || endExclusive == null || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("月度状态范围重建要求 startInclusive < endExclusive");
        }
        LocalDate startMonth = startInclusive.toLocalDate().withDayOfMonth(1);
        LocalDate endMonth = endExclusive.minusNanos(1).toLocalDate().withDayOfMonth(1);
        int total = 0;
        LocalDate month = startMonth;
        while (!month.isAfter(endMonth)) {
            int inserted = monthlyStateInitService.initMonth(month);
            int recalculated = monthlyStateInitService.recalculateMonthDrafts(month);
            int confirmed = monthlyStateInitService.autoConfirmDraftStates(month);
            total += inserted + recalculated + confirmed;
            log.info("月度状态范围重建-完成月 {}, inserted={}, recalculated={}, confirmed={}",
                    month, inserted, recalculated, confirmed);
            month = month.plusMonths(1);
        }
        log.info("月度状态范围重建-完成, start={}, end={}, totalChange={}", startInclusive, endExclusive, total);
        return total;
    }
}
