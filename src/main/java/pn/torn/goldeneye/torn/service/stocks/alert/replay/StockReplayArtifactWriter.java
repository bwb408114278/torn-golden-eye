package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * 回放研究产物写入器。
 *
 * <p>所有文件先写临时文件，全部成功后再原子移动，避免产生伪完成结果。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public class StockReplayArtifactWriter {
    private static final String TRADES_HEADER =
            "runId,track,stocksId,strategy,entryTime,exitTime,entryPrice,exitPrice,quantity,grossReturn,netReturn,closeType\n";
    private static final String REJECTIONS_HEADER =
            "runId,track,stocksId,strategy,roundTime,rejectReason,observationResult,laterMfe,laterMae\n";
    private static final String EQUITY_HEADER =
            "runId,track,time,equity,cash,invested,status,missingStocks\n";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 原子写入四类研究产物。
     *
     * @param summary 摘要
     * @param trades 交易记录
     * @param rejections 拒绝记录
     * @param equityPoints 净值点
     * @param outputDirectory 输出目录
     * @throws IOException 文件写入异常
     */
    public void write(StockReplaySummary summary, List<StockReplayTrade> trades,
                      List<StockReplayRejection> rejections,
                      List<StockReplayEquityPoint> equityPoints,
                      Path outputDirectory) throws IOException {
        Objects.requireNonNull(summary, "summary不能为空");
        Objects.requireNonNull(outputDirectory, "outputDirectory不能为空");
        Files.createDirectories(outputDirectory);
        String prefix = summary.runId() + "-";
        Path summaryFile = outputDirectory.resolve(prefix + "summary.json");
        Path tradesFile = outputDirectory.resolve(prefix + "trades.csv");
        Path rejectionsFile = outputDirectory.resolve(prefix + "rejections.csv");
        Path equityFile = outputDirectory.resolve(prefix + "equity-curve.csv");
        if (Files.exists(summaryFile) || Files.exists(tradesFile)
                || Files.exists(rejectionsFile) || Files.exists(equityFile)) {
            throw new IllegalStateException("回放完成产物已存在,拒绝覆盖: " + summary.runId());
        }
        Path summaryTmp = outputDirectory.resolve(prefix + "summary.json.tmp");
        Path tradesTmp = outputDirectory.resolve(prefix + "trades.csv.tmp");
        Path rejectionsTmp = outputDirectory.resolve(prefix + "rejections.csv.tmp");
        Path equityTmp = outputDirectory.resolve(prefix + "equity-curve.csv.tmp");
        try {
            Files.writeString(summaryTmp, objectMapper.writeValueAsString(summary), StandardCharsets.UTF_8);
            Files.writeString(tradesTmp, TRADES_HEADER + joinTrades(trades), StandardCharsets.UTF_8);
            Files.writeString(rejectionsTmp, REJECTIONS_HEADER + joinRejections(rejections), StandardCharsets.UTF_8);
            Files.writeString(equityTmp, EQUITY_HEADER + joinEquity(equityPoints), StandardCharsets.UTF_8);
            move(summaryTmp, summaryFile);
            move(tradesTmp, tradesFile);
            move(rejectionsTmp, rejectionsFile);
            move(equityTmp, equityFile);
        } catch (IOException | RuntimeException exception) {
            deleteIfExists(summaryTmp, tradesTmp, rejectionsTmp, equityTmp);
            throw exception;
        }
    }

    private void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private void deleteIfExists(Path... paths) throws IOException {
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private String joinTrades(List<StockReplayTrade> trades) {
        return trades == null ? "" : trades.stream()
                .map(item -> String.join(",", text(item.runId()), text(item.track()), text(item.stocksId()),
                        text(item.strategy()), text(item.entryTime()), text(item.exitTime()),
                        text(item.entryPrice()), text(item.exitPrice()), Long.toString(item.quantity()),
                        text(item.grossReturn()), text(item.netReturn()), text(item.closeType())))
                .reduce("", (left, right) -> left + right + "\n");
    }

    private String joinRejections(List<StockReplayRejection> rejections) {
        return rejections == null ? "" : rejections.stream()
                .map(item -> String.join(",", text(item.runId()), text(item.track()), text(item.stocksId()),
                        text(item.strategy()), text(item.roundTime()), text(item.rejectReason()),
                        text(item.observationResult()), text(item.laterMfe()), text(item.laterMae())))
                .reduce("", (left, right) -> left + right + "\n");
    }

    private String joinEquity(List<StockReplayEquityPoint> points) {
        return points == null ? "" : points.stream()
                .map(item -> String.join(",", text(item.runId()), text(item.track()), text(item.time()),
                        text(item.equity()), text(item.cash()), text(item.invested()), text(item.status()),
                        text(item.missingStocks())))
                .reduce("", (left, right) -> left + right + "\n");
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
