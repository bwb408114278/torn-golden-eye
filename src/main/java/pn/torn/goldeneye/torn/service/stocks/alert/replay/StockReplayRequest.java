package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 股票隔离回放请求。
 *
 * @param portfolioId           研究组合标识
 * @param startTime             输入起始时间(含)
 * @param endTime               输入结束时间(不含)
 * @param barBuildVersion       bar构建版本
 * @param featureVersion        特征版本
 * @param buyRuleVersion        买入规则版本
 * @param sellRuleVersion       卖出规则版本
 * @param allocationRuleVersion 分配规则版本
 * @param messageRuleVersion    消息规则版本
 * @param outputDirectory       研究产物输出目录
 * @param tracks                回放轨道
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplayRequest(
        String portfolioId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String barBuildVersion,
        String featureVersion,
        String buyRuleVersion,
        String sellRuleVersion,
        String allocationRuleVersion,
        String messageRuleVersion,
        Path outputDirectory,
        Set<StockReplayTrackEnum> tracks
) {
    /**
     * 正式轨道槽位数量。
     */
    public static final int FORMAL_SLOT_COUNT = 5;
    /**
     * 正式轨道每槽初始资金。
     */
    public static final BigDecimal FORMAL_SLOT_INITIAL_CASH = new BigDecimal("2000000000.00");

    /**
     * 规范化和校验请求。
     */
    public StockReplayRequest {
        portfolioId = requireText(portfolioId, "portfolioId");
        Objects.requireNonNull(startTime, "startTime不能为空");
        Objects.requireNonNull(endTime, "endTime不能为空");
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("startTime必须早于endTime");
        }
        barBuildVersion = requireText(barBuildVersion, "barBuildVersion");
        featureVersion = requireText(featureVersion, "featureVersion");
        buyRuleVersion = requireText(buyRuleVersion, "buyRuleVersion");
        sellRuleVersion = requireText(sellRuleVersion, "sellRuleVersion");
        allocationRuleVersion = requireText(allocationRuleVersion, "allocationRuleVersion");
        messageRuleVersion = requireText(messageRuleVersion, "messageRuleVersion");
        Objects.requireNonNull(outputDirectory, "outputDirectory不能为空");
        if (outputDirectory.toString().isBlank()) {
            throw new IllegalArgumentException("outputDirectory不能为空");
        }
        Objects.requireNonNull(tracks, "tracks不能为空");
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("至少选择一条回放轨道");
        }
        tracks = Set.copyOf(EnumSet.copyOf(tracks));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + "不能为空");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return normalized;
    }
}
