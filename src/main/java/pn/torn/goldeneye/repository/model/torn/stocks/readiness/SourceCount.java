package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

/**
 * 数据来源计数。
 *
 * @param source 数据来源编码
 * @param count  行数
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record SourceCount(
        String source,
        long count) {
}
