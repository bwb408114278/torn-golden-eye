package pn.torn.goldeneye.torn.service.stocks.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tornsy m1 分钟报价解析器 - 解析 m1 数组并对每行做严格校验
 * <p>
 * m1 数组口径（下标 0 为 Unix epoch 秒、1 为价格、2 为总股数、3 为可选市值）：
 * 仅保留满足「整分钟、窗口内、早于稳定截止、价格/总股数/可选市值为正」的合法行，
 * 非法行直接拒绝且不补值，不使用前值/后值/当前值或 OHLC 补偿失败行。
 * 同一响应内同股票同分钟出现价格或总股数冲突的行同样拒绝。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
@Component
public class TornsyMinuteQuoteParser {

    /**
     * 业务时区
     */
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 市值解析无效哨兵值（有效市值必为正数，Long.MIN_VALUE 不会与合法值冲突）
     */
    private static final Long INVALID_MARKET_CAP = Long.MIN_VALUE;

    /**
     * 将 Unix epoch 秒转换为 Asia/Shanghai 自然分钟时间（秒与纳秒均为 0）
     *
     * @param epochSecond Unix epoch 秒（须为整分钟）
     * @return 对应自然分钟时间
     */
    public static LocalDateTime toMinuteTime(long epochSecond) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZONE_ID)
                .withSecond(0).withNano(0);
    }

    /**
     * 解析并校验 m1 数据行，返回去重后的合法报价列表
     * <p>
     * 对每行执行结构、数值、整分钟、时间窗口与稳定截止校验，非法行拒绝；
     * 同一分钟出现价格或总股数冲突时拒绝后出现的行，同一分钟值相同的重复行只保留一条。
     *
     * @param rows               m1 原始行数组（可为 null）
     * @param requestStart       请求窗口开始时间（含）
     * @param requestEnd         请求窗口结束时间（不含）
     * @param stableEndExclusive 稳定截止时间（早于此值的数据才可回填，不含）
     * @return 去重后的合法报价列表（可能为空）
     */
    public List<TornsyMinuteQuote> parse(List<JsonNode> rows, LocalDateTime requestStart,
                                         LocalDateTime requestEnd, LocalDateTime stableEndExclusive) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<TornsyMinuteQuote> result = new ArrayList<>(rows.size());
        Map<LocalDateTime, TornsyMinuteQuote> byMinute = new HashMap<>();
        for (JsonNode row : rows) {
            TornsyMinuteQuote quote = parseRow(row, requestStart, requestEnd, stableEndExclusive);
            if (quote != null && byMinute.putIfAbsent(quote.minuteTime(), quote) == null) {
                result.add(quote);
            }
        }
        return result;
    }

    /**
     * 解析并校验单个 m1 数据行
     *
     * @param row                单个 m1 数组节点
     * @param requestStart       请求窗口开始时间（含）
     * @param requestEnd         请求窗口结束时间（不含）
     * @param stableEndExclusive 稳定截止时间（不含）
     * @return 合法报价；非法行返回 null
     */
    private TornsyMinuteQuote parseRow(JsonNode row, LocalDateTime requestStart,
                                       LocalDateTime requestEnd, LocalDateTime stableEndExclusive) {
        Long epochSecond = parseEpochSecond(row);
        if (epochSecond == null) {
            return null;
        }

        LocalDateTime minuteTime = toMinuteTime(epochSecond);
        if (minuteTime.isBefore(requestStart) || !minuteTime.isBefore(requestEnd)) {
            return null;
        }
        if (!minuteTime.isBefore(stableEndExclusive)) {
            return null;
        }

        BigDecimal price = parsePrice(row.get(1));
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        Long totalShares = parseTotalShares(row.get(2));
        if (totalShares == null) {
            return null;
        }

        Long marketCap = parseMarketCap(row);
        if (INVALID_MARKET_CAP.equals(marketCap)) {
            return null;
        }
        return new TornsyMinuteQuote(minuteTime, price, totalShares, marketCap);
    }

    /**
     * 校验数组结构并解析整分钟 epoch 秒
     *
     * @param row 单个 m1 数组节点
     * @return epoch 秒；非法（非数组、长度错误、epoch 非数值或非整分钟）时返回 null
     */
    private Long parseEpochSecond(JsonNode row) {
        if (row == null || !row.isArray()) {
            return null;
        }
        int size = row.size();
        if (size < 3 || size > 4) {
            return null;
        }
        if (!row.get(0).isNumber()) {
            return null;
        }
        long epochSecond = row.get(0).asLong();
        if (epochSecond % 60 != 0) {
            return null;
        }
        return epochSecond;
    }

    /**
     * 解析并校验总股数（必为正数）
     *
     * @param node 总股数节点
     * @return 总股数；非法（非数值或非正数）时返回 null
     */
    private Long parseTotalShares(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        long totalShares = node.asLong();
        return totalShares > 0 ? totalShares : null;
    }

    /**
     * 解析并校验可选市值
     *
     * @param row 单个 m1 数组节点（已通过结构与长度校验）
     * @return 市值（缺失时为 null）；非法（非数值或非正数）时返回 {@link #INVALID_MARKET_CAP}
     */
    private Long parseMarketCap(JsonNode row) {
        if (row.size() < 4) {
            return null;
        }
        JsonNode capNode = row.get(3);
        if (capNode == null || capNode.isNull()) {
            return null;
        }
        if (!capNode.isNumber()) {
            return INVALID_MARKET_CAP;
        }
        long cap = capNode.asLong();
        return cap > 0 ? cap : INVALID_MARKET_CAP;
    }

    /**
     * 解析价格字段（兼容字符串与数值两种形态）
     *
     * @param node 价格节点
     * @return 价格；非法或不可解析时返回 null
     */
    private BigDecimal parsePrice(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                return new BigDecimal(node.asText().trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }
}
