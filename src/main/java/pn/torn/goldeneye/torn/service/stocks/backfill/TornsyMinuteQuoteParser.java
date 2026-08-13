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
            if (quote == null) {
                continue;
            }
            TornsyMinuteQuote existing = byMinute.get(quote.minuteTime());
            if (existing != null) {
                boolean conflict = existing.price().compareTo(quote.price()) != 0
                        || existing.totalShares() != quote.totalShares();
                if (conflict) {
                    continue;
                }
                continue;
            }
            byMinute.put(quote.minuteTime(), quote);
            result.add(quote);
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

        if (!row.get(2).isNumber()) {
            return null;
        }
        long totalShares = row.get(2).asLong();
        if (totalShares <= 0) {
            return null;
        }

        Long marketCap = null;
        if (size == 4) {
            JsonNode capNode = row.get(3);
            if (capNode != null && !capNode.isNull()) {
                if (!capNode.isNumber()) {
                    return null;
                }
                long cap = capNode.asLong();
                if (cap <= 0) {
                    return null;
                }
                marketCap = cap;
            }
        }

        return new TornsyMinuteQuote(minuteTime, price, totalShares, marketCap);
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
