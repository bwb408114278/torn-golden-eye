package pn.torn.goldeneye.torn.service.stocks.readiness;

import java.time.LocalDateTime;

/**
 * 股票数据就绪只读报告模型。
 * <p>
 * 用于本地只读环境生成 JSON/Markdown 数据就绪报告，包含运行标识、输入范围、
 * 当前版本、关键统计与生成时刻。该模型不写生产业务表，不承载开关决策。
 *
 * @param runId           本次报告运行标识
 * @param generatedAt     报告生成时刻
 * @param startInclusive  输入范围起始时间（含）
 * @param endExclusive    输入范围结束时间（不含）
 * @param barBuildVersion 当前 bar 构建版本
 * @param featureVersion  当前 feature 计算版本
 * @param manifestHash    输入清单哈希（由范围、版本和统计事实计算，不用空串）
 * @param snapshot        真实只读统计快照
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
        String manifestHash,
        StockDataReadinessSnapshot snapshot
) {
}
