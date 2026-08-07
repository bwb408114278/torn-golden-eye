package pn.torn.goldeneye.torn.service.stocks.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.service.stocks.replay.model.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 回放产物落盘写入器。
 *
 * <p>{@link #writeCompleted} 将一次成功回放的完整产物集合写入 {@code outputRootDir}/{@code runId}
 * 目录,产物包含: {@code summary.json}、{@code trades.csv}、{@code rejections.csv}、
 * {@code equity-curve.csv}。写入具备幂等保护与原子性: 仅完整四产物且摘要状态为
 * {@code COMPLETED} 成为不可覆盖的完成标识,相同 {@code runId + sourceManifestHash} 拒绝覆盖;
 * 每个文件先写入同目录临时文件,全部就绪后再逐个原子改名,summary 最后改名,任一步失败即
 * 清理临时文件与目标目录,不留伪完成摘要。</p>
 *
 * <p>{@link #writeFailed} 将失败诊断摘要写入独立 {@code attemptId} 目录
 * ({@code outputRootDir}/{@code runId}-failed/{@code attemptId}),不占用成功产物目录与完成标识,
 * 同一 {@code runId} 失败后允许新 attempt 从头重跑。</p>
 *
 * <p>CSV 采用 UTF-8 无 BOM 编码,行尾 {@code \n};时间使用 ISO_LOCAL_DATE_TIME,金额使用
 * BigDecimal 的 plain 字符串,{@code null} 输出空串。JSON 使用独立配置的 ObjectMapper 序列化
 * (JavaTimeModule + 非时间戳),输出格式化 JSON。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@Slf4j
@Component
public class StockReplayResultWriter {

    /**
     * 摘要文件名后缀(同时作为幂等完成标记)。
     */
    private static final String SUMMARY_FILE_SUFFIX = "-summary.json";

    /**
     * 交易明细文件名后缀。
     */
    private static final String TRADES_FILE_SUFFIX = "-trades.csv";

    /**
     * 拒绝/观察记录文件名后缀。
     */
    private static final String REJECTIONS_FILE_SUFFIX = "-rejections.csv";

    /**
     * 净值曲线文件名后缀。
     */
    private static final String EQUITY_CURVE_FILE_SUFFIX = "-equity-curve.csv";

    /**
     * 交易明细 CSV 表头(列序与 StockReplayTrade 组件一致)。
     */
    private static final String TRADES_HEADER =
            "runId,track,roundTime,stocksId,stocksShortname,side,strategyType,signalTime,"
                    + "entryTime,exitTime,quantity,entryPrice,exitPrice,investedCash,sellProceeds,"
                    + "netReturn,closeType,reasonCode,batchNo,holdHours";

    /**
     * 拒绝/观察记录 CSV 表头(列序与 StockReplayRejection 组件一致)。
     */
    private static final String REJECTIONS_HEADER =
            "runId,track,roundTime,stocksId,stocksShortname,strategyType,qualityScore,monthlyStyle,"
                    + "riskLevel,eligibilityResult,eligibilityReasons,candidateRank,portfolioDecision,"
                    + "rejectReason,observationResult,laterMfe,laterMae,theoreticalEntryTime,"
                    + "theoreticalEntryPrice,theoreticalExitSignalTime,theoreticalExitTime,"
                    + "theoreticalExitPrice,theoreticalCloseType,theoreticalNetReturn";

    /**
     * 净值曲线 CSV 表头(列序与 StockReplayEquityPoint 组件一致)。
     */
    private static final String EQUITY_CURVE_HEADER =
            "runId,track,roundTime,equity,cashAndReserved,openPositions,realizedReturn,utilization";

    /**
     * CSV 时间单元格格式。
     */
    private static final DateTimeFormatter ISO_LOCAL_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * JSON 序列化器:注册 JavaTimeModule 且禁用时间戳,时间输出为 ISO 格式。
     */
    private final ObjectMapper objectMapper;

    /**
     * 无参构造器,创建独立配置的 ObjectMapper(与项目 JsonUtils 时间格式不同,此处统一 ISO)。
     */
    public StockReplayResultWriter() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 将成功回放产物写入 {@code outputRootDir}/{@code runId} 目录。
     *
     * <p>写入流程: 完成标识校验 → 建目录 → 四类产物依次写入同目录临时文件({@code .tmp.<uuid>})
     * → 全部就绪后按 trades / rejections / equity-curve / summary 顺序原子改名。summary 最后
     * 改名,保证完成标识仅在全部产物就绪后出现。完成标识由 {@code runId + sourceManifestHash}
     * 共同决定: 已存在同代际 {@code COMPLETED} 摘要时拒绝覆盖;已被不同输入代际的完成结果占用
     * 时同样拒绝,避免不同代际产物混淆。任一步失败时清理已创建的临时文件并递归删除目标目录。
     * 已存在的非 {@code COMPLETED} 摘要(历史遗留)允许重写。</p>
     *
     * @param runId         回放请求归一化键
     * @param outputRootDir 产物输出根目录(可为相对路径,如 {@code .hermes/output/vip-stock-replay})
     * @param result        成功回放产物集合(摘要状态必须为COMPLETED)
     * @return 实际写入的产物目录绝对路径
     * @throws IllegalStateException 已完成同代际/不同代际占用、标识文件无法解析或写入失败时抛出
     */
    public String writeCompleted(String runId, String outputRootDir, StockReplayResult result) {
        Path targetDir = Paths.get(outputRootDir).resolve(runId).toAbsolutePath().normalize();
        Path summaryFile = targetDir.resolve(runId + SUMMARY_FILE_SUFFIX);
        verifyNotCompleted(runId, summaryFile, result.summary());

        List<Path> tempFiles = new ArrayList<>(4);
        try {
            Files.createDirectories(targetDir);
            tempFiles.add(writeTextAtomic(newTempFile(targetDir, runId + TRADES_FILE_SUFFIX), renderTrades(result.trades())));
            tempFiles.add(writeTextAtomic(newTempFile(targetDir, runId + REJECTIONS_FILE_SUFFIX), renderRejections(result.rejections())));
            tempFiles.add(writeTextAtomic(newTempFile(targetDir, runId + EQUITY_CURVE_FILE_SUFFIX), renderEquityPoints(result.equityPoints())));
            tempFiles.add(writeJsonAtomic(newTempFile(targetDir, runId + SUMMARY_FILE_SUFFIX), result.summary()));

            renameAtomic(tempFiles.get(0), targetDir.resolve(runId + TRADES_FILE_SUFFIX));
            renameAtomic(tempFiles.get(1), targetDir.resolve(runId + REJECTIONS_FILE_SUFFIX));
            renameAtomic(tempFiles.get(2), targetDir.resolve(runId + EQUITY_CURVE_FILE_SUFFIX));
            renameAtomic(tempFiles.get(3), summaryFile);

            log.info("回放产物写入完成, runId={}, 目录={}", runId, targetDir);
            return targetDir.toString();
        } catch (Exception e) {
            cleanup(tempFiles, targetDir);
            log.error("回放产物写入失败并已清理, runId={}, 目录={}", runId, targetDir, e);
            throw new IllegalStateException("回放产物写入失败, runId=" + runId, e);
        }
    }

    /**
     * 将失败诊断摘要写入独立 attemptId 目录,不占用成功产物目录与完成标识。
     *
     * @param runId         回放请求归一化键
     * @param attemptId     本次尝试标识(同一runId每次失败均不同)
     * @param outputRootDir 产物输出根目录
     * @param result        失败回放结果(摘要状态为FAILED)
     * @return 实际写入的失败摘要目录绝对路径
     */
    public String writeFailed(String runId, String attemptId, String outputRootDir, StockReplayResult result) {
        Path failedDir = Paths.get(outputRootDir).resolve(runId + "-failed")
                .resolve(attemptId).toAbsolutePath().normalize();
        Path summaryFile = failedDir.resolve(runId + SUMMARY_FILE_SUFFIX);
        try {
            Files.createDirectories(failedDir);
            Path tempFile = newTempFile(failedDir, runId + SUMMARY_FILE_SUFFIX);
            writeJsonAtomic(tempFile, result.summary());
            renameAtomic(tempFile, summaryFile);
            log.info("回放失败摘要写入: runId={}, attemptId={}, 目录={}", runId, attemptId, failedDir);
            return failedDir.toString();
        } catch (Exception e) {
            deleteRecursively(failedDir);
            log.error("回放失败摘要写出失败并已清理, runId={}, attemptId={}", runId, attemptId, e);
            throw new IllegalStateException("回放失败摘要写入失败, runId=" + runId, e);
        }
    }

    /**
     * 校验完成标识: 存在同代际或不同代际的COMPLETED摘要时拒绝覆盖。
     *
     * @param runId       回放请求归一化键
     * @param summaryFile 完成标识文件
     * @param current     本次回放摘要
     * @throws IllegalStateException 已完成(同代际)、被不同代际占用或标识无法解析时抛出
     */
    private void verifyNotCompleted(String runId, Path summaryFile, StockReplaySummary current) {
        if (!Files.exists(summaryFile)) {
            return;
        }
        StockReplaySummary existing = readSummary(summaryFile);
        if (existing == null) {
            throw new IllegalStateException("该 runId 已有产物标识文件但内容无法解析,拒绝覆盖: " + summaryFile);
        }
        String currentHash = current.sourceManifest() == null ? null : current.sourceManifest().sha256();
        String existingHash = existing.sourceManifest() == null ? null : existing.sourceManifest().sha256();
        if (isCompleted(existing)) {
            if (java.util.Objects.equals(existingHash, currentHash)) {
                throw new IllegalStateException(
                        "该 runId 已以相同输入代际完成,拒绝覆盖: " + summaryFile + ", sourceManifestHash=" + existingHash);
            }
            throw new IllegalStateException(
                    "该 runId 已被不同输入代际的成功结果占用,需清理后重跑: " + summaryFile
                            + ", 既有hash=" + existingHash + ", 本次hash=" + currentHash);
        }
        log.warn("检测到历史非COMPLETED摘要,允许重写: runId={}, status={}", runId, existing.status());
    }

    private static boolean isCompleted(StockReplaySummary summary) {
        return "COMPLETED".equals(summary.status());
    }

    /**
     * 读取已有完成标识摘要。
     *
     * @param summaryFile 完成标识文件
     * @return 摘要;读取失败时返回null
     */
    private StockReplaySummary readSummary(Path summaryFile) {
        try {
            return objectMapper.readValue(summaryFile.toFile(), StockReplaySummary.class);
        } catch (IOException e) {
            log.warn("读取已有回放摘要失败: {}", summaryFile, e);
            return null;
        }
    }

    /**
     * 渲染交易明细 CSV(表头 + 逐行数据)。
     *
     * @param trades 交易明细列表
     * @return CSV 文本内容
     */
    private String renderTrades(List<StockReplayTrade> trades) {
        StringBuilder sb = new StringBuilder(TRADES_HEADER.length() + 64);
        sb.append(TRADES_HEADER).append('\n');
        if (trades != null) {
            for (StockReplayTrade trade : trades) {
                sb.append(toCsvRow(trade)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 渲染拒绝/观察记录 CSV(表头 + 逐行数据)。
     *
     * @param rejections 拒绝/观察记录列表
     * @return CSV 文本内容
     */
    private String renderRejections(List<StockReplayRejection> rejections) {
        StringBuilder sb = new StringBuilder(REJECTIONS_HEADER.length() + 64);
        sb.append(REJECTIONS_HEADER).append('\n');
        if (rejections != null) {
            for (StockReplayRejection rejection : rejections) {
                sb.append(toCsvRow(rejection)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 渲染净值曲线 CSV(表头 + 逐行数据)。
     *
     * @param equityPoints 净值点列表
     * @return CSV 文本内容
     */
    private String renderEquityPoints(List<StockReplayEquityPoint> equityPoints) {
        StringBuilder sb = new StringBuilder(EQUITY_CURVE_HEADER.length() + 64);
        sb.append(EQUITY_CURVE_HEADER).append('\n');
        if (equityPoints != null) {
            for (StockReplayEquityPoint point : equityPoints) {
                sb.append(toCsvRow(point)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 将单条交易记录转换为 CSV 行(列序与表头严格一致)。
     *
     * @param trade 交易记录
     * @return CSV 行
     */
    private String toCsvRow(StockReplayTrade trade) {
        return String.join(",",
                csvCell(trade.runId()),
                csvCell(trade.track()),
                csvCell(trade.roundTime()),
                csvCell(trade.stocksId()),
                csvCell(trade.stocksShortname()),
                csvCell(trade.side()),
                csvCell(trade.strategyType()),
                csvCell(trade.signalTime()),
                csvCell(trade.entryTime()),
                csvCell(trade.exitTime()),
                csvCell(trade.quantity()),
                csvCell(trade.entryPrice()),
                csvCell(trade.exitPrice()),
                csvCell(trade.investedCash()),
                csvCell(trade.sellProceeds()),
                csvCell(trade.netReturn()),
                csvCell(trade.closeType()),
                csvCell(trade.reasonCode()),
                csvCell(trade.batchNo()),
                csvCell(trade.holdHours())
        );
    }

    /**
     * 将单条拒绝/观察记录转换为 CSV 行(列序与表头严格一致)。
     *
     * @param rejection 拒绝/观察记录
     * @return CSV 行
     */
    private String toCsvRow(StockReplayRejection rejection) {
        return String.join(",",
                csvCell(rejection.runId()),
                csvCell(rejection.track()),
                csvCell(rejection.roundTime()),
                csvCell(rejection.stocksId()),
                csvCell(rejection.stocksShortname()),
                csvCell(rejection.strategyType()),
                csvCell(rejection.qualityScore()),
                csvCell(rejection.monthlyStyle()),
                csvCell(rejection.riskLevel()),
                csvCell(rejection.eligibilityResult()),
                csvCell(rejection.eligibilityReasons()),
                csvCell(rejection.candidateRank()),
                csvCell(rejection.portfolioDecision()),
                csvCell(rejection.rejectReason()),
                csvCell(rejection.observationResult()),
                csvCell(rejection.laterMfe()),
                csvCell(rejection.laterMae()),
                csvCell(rejection.theoreticalEntryTime()),
                csvCell(rejection.theoreticalEntryPrice()),
                csvCell(rejection.theoreticalExitSignalTime()),
                csvCell(rejection.theoreticalExitTime()),
                csvCell(rejection.theoreticalExitPrice()),
                csvCell(rejection.theoreticalCloseType()),
                csvCell(rejection.theoreticalNetReturn())
        );
    }

    /**
     * 将单条净值点转换为 CSV 行(列序与表头严格一致)。
     *
     * @param point 净值点
     * @return CSV 行
     */
    private String toCsvRow(StockReplayEquityPoint point) {
        return String.join(",",
                csvCell(point.runId()),
                csvCell(point.track()),
                csvCell(point.roundTime()),
                csvCell(point.equity()),
                csvCell(point.cashAndReserved()),
                csvCell(point.openPositions()),
                csvCell(point.realizedReturn()),
                csvCell(point.utilization())
        );
    }

    /**
     * 将单元格值转为 CSV 文本: {@code null} 为空串,LocalDateTime 用 ISO 格式,BigDecimal 用
     * plain 字符串,其余类型直接 toString。项目数据不含逗号/换行,无需转义。
     *
     * @param value 单元格值
     * @return CSV 单元格文本
     */
    private String csvCell(Object value) {
        return switch (value) {
            case null -> "";
            case LocalDateTime dateTime -> dateTime.format(ISO_LOCAL_DATE_TIME);
            case BigDecimal decimal -> decimal.toPlainString();
            default -> String.valueOf(value);
        };
    }

    /**
     * 生成同目录下的临时文件路径(后缀 {@code .tmp.<uuid>})。
     *
     * @param targetDir 目标目录
     * @param fileName  最终文件名
     * @return 临时文件路径
     */
    private Path newTempFile(Path targetDir, String fileName) {
        return targetDir.resolve(fileName + ".tmp." + UUID.randomUUID());
    }

    /**
     * 将文本内容原子写入临时文件(UTF-8 无 BOM)。
     *
     * @param tempFile 临时文件路径
     * @param content  文本内容
     * @return 写入的临时文件路径
     * @throws IOException 写入失败时抛出
     */
    private Path writeTextAtomic(Path tempFile, String content) throws IOException {
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        return tempFile;
    }

    /**
     * 将对象序列化为格式化 JSON 后原子写入临时文件(UTF-8 无 BOM)。
     *
     * @param tempFile 临时文件路径
     * @param value    待序列化对象(支持嵌套 record 与 TreeMap)
     * @return 写入的临时文件路径
     * @throws IOException 写入失败时抛出
     */
    private Path writeJsonAtomic(Path tempFile, Object value) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        Files.writeString(tempFile, json, StandardCharsets.UTF_8);
        return tempFile;
    }

    /**
     * 将临时文件原子改名为目标文件;文件系统不支持 ATOMIC_MOVE 时回退 REPLACE_EXISTING。
     *
     * @param tempFile 临时文件路径
     * @param target   目标文件路径
     * @throws IOException 改名失败时抛出
     */
    private void renameAtomic(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 清理已创建的临时文件并递归删除目标目录(不触及上层输出根目录)。
     *
     * @param tempFiles 已创建的临时文件
     * @param targetDir 目标目录
     */
    private void cleanup(List<Path> tempFiles, Path targetDir) {
        for (Path tempFile : tempFiles) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("清理回放临时文件失败: {}", tempFile, e);
            }
        }
        deleteRecursively(targetDir);
    }

    /**
     * 递归删除目录及其内容。
     *
     * @param dir 待删除目录
     */
    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理回放产物目录失败: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("遍历回放产物目录失败: {}", dir, e);
        }
    }
}
