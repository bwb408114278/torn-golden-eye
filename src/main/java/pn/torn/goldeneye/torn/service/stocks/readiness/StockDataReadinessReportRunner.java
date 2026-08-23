package pn.torn.goldeneye.torn.service.stocks.readiness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 股票数据就绪报告本地只读运行器。
 * <p>
 * 由 AI 在本地订阅库追平后显式调用，不注册生产调度或 Bot 指令，因此生产代码中
 * 没有自动调用点是预期行为，并非冗余声明。当前实现提供统一 runId 与报告模型
 * 组装入口，具体统计加载由调用方/后续只读 SQL 复核完成。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDataReadinessReportRunner {

    private final StockDataReadinessReportWriter writer;

    /**
     * 生成并输出一份指定范围的空统计报告（统计字段由调用方在后续只读复核中填充）。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 生成的 summary.json 路径
     */
    public java.nio.file.Path run(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        StockDataReadinessReport report = new StockDataReadinessReport(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                startInclusive,
                endExclusive,
                "1.0.0",
                "1.0.0",
                0, 0, 0, 0, 0, 0, 0, 0, "");
        try {
            return writer.write(java.nio.file.Path.of(".hermes", "output", "vip-stock-readiness"), report);
        } catch (Exception e) {
            throw new IllegalStateException("数据就绪报告生成失败", e);
        }
    }
}
