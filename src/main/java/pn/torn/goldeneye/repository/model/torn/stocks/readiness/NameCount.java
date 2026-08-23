package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

/**
 * 名称分组计数。
 *
 * @param name  分组名称（如不可用原因、质量原因）
 * @param count 数量
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record NameCount(
        String name,
        long count) {
}
