package pn.torn.goldeneye.torn.service.stocks.readiness;

import java.time.LocalDateTime;

/**
 * 股票数据就绪只读报告模型。
 * <p>
 * 用于本地只读环境生成 JSON/Markdown 数据就绪报告，包含运行标识、输入范围、
 * 当前版本、关键统计与生成时刻。该模型不写生产业务表，不承载开关决策。
 *
 * @param runId                      本次报告运行标识
 * @param generatedAt                报告生成时刻
 * @param startInclusive             输入范围起始时间（含）
 * @param endExclusive               输入范围结束时间（不含）
 * @param barBuildVersion            当前 bar 构建版本
 * @param featureVersion             当前 feature 计算版本
 * @param stockCount                 参与统计的股票数量
 * @param minuteFactCount            分钟事实行数
 * @param barCount                   bar 行数
 * @param usableBarCount             可用 bar 行数
 * @param featureCount               feature 行数
 * @param repairedDataOnlyRoundCount REPAIRED_DATA_ONLY 轮次数
 * @param draftMonthCount            DRAFT 月度状态数
 * @param confirmedMonthCount        CONFIRMED 月度状态数
 * @param manifestHash               输入清单哈希（仅用于一致性观测）
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record StockDataReadinessReport(
        String runId,
        LocalDateTime generatedAt,
        LocalDateTime startInclusive,
        LocalDateTime endExclusive,
        String barBuildVersion,
        String featureVersion,
        int stockCount,
        long minuteFactCount,
        long barCount,
        long usableBarCount,
        long featureCount,
        long repairedDataOnlyRoundCount,
        long draftMonthCount,
        long confirmedMonthCount,
        String manifestHash
) {
}
