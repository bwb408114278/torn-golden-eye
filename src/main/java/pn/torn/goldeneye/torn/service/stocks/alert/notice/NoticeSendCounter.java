package pn.torn.goldeneye.torn.service.stocks.alert.notice;

/**
 * 股票通知单轮发送计数 - 按消息条数统计成功与失败。
 * <p>
 * 每次{@code sendPendingNotices}调用使用一个独立实例,普通通知与α换仓关联组共用同一计数,
 * 使一轮发送日志中的成功/失败条数覆盖全部发送路径。计数按消息条数统计,不按通知条数统计。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
public class NoticeSendCounter {
    /**
     * 发送成功条数。
     */
    private int successCount;
    /**
     * 发送失败或fail-closed条数。
     */
    private int failedCount;

    /**
     * 记录一条发送成功。
     */
    public void countSuccess() {
        successCount++;
    }

    /**
     * 记录一条发送失败或fail-closed。
     */
    public void countFailure() {
        failedCount++;
    }

    /**
     * 返回发送成功条数。
     *
     * @return 发送成功条数
     */
    public int successCount() {
        return successCount;
    }

    /**
     * 返回发送失败条数。
     *
     * @return 发送失败或fail-closed条数
     */
    public int failedCount() {
        return failedCount;
    }
}
