package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

/**
 * 轮次状态计数。
 *
 * @param roundStatus 轮次状态
 * @param count       数量
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record RoundStatusCount(
        String roundStatus,
        long count) {
}
