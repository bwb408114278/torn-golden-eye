package pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeRebalanceGroupStatusEnum;

/**
 * α换仓关联组回写结果 - 组级领取、成功、失败与异常收敛的显式结果对象
 * <p>
 * 组级回写必须返回可判定的结果而不是日志:实际更新行数不等于预期行数时,
 * 调用方不得宣称"两腿已完整送达"或"两腿已一致失败",必须进入组级恢复或人工核验路径。
 *
 * @param expectedRows         本次组级回写预期更新的行数(完整两腿为2)
 * @param actualRows           数据库实际更新行数
 * @param groupStatus          本次回写期望达成的组级状态
 * @param requiresRecovery     实际行数不足时返回true,表示必须读取完整关联组并收敛
 * @param requiresManualReview 组状态进入FAILED_FINAL/INCONSISTENT时返回true,表示需要人工核验
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.13
 */
public record RebalanceGroupWriteResult(
        int expectedRows,
        int actualRows,
        StockNoticeRebalanceGroupStatusEnum groupStatus,
        boolean requiresRecovery,
        boolean requiresManualReview) {

    /**
     * 构造组级回写结果,并依据行数与目标组状态推导恢复/人工核验要求。
     *
     * @param expectedRows 预期更新行数
     * @param actualRows   实际更新行数
     * @param groupStatus  本次回写期望达成的组级状态
     * @return 组级回写结果
     */
    public static RebalanceGroupWriteResult of(int expectedRows, int actualRows,
                                               StockNoticeRebalanceGroupStatusEnum groupStatus) {
        boolean complete = expectedRows > 0 && actualRows == expectedRows;
        boolean manualReview = groupStatus == StockNoticeRebalanceGroupStatusEnum.FAILED_FINAL
                || groupStatus == StockNoticeRebalanceGroupStatusEnum.INCONSISTENT;
        return new RebalanceGroupWriteResult(expectedRows, actualRows, groupStatus, !complete, manualReview);
    }

    /**
     * 判断本次组级回写是否完整更新了预期行数。
     *
     * @return 实际行数等于预期行数时返回true
     */
    public boolean complete() {
        return expectedRows > 0 && actualRows == expectedRows;
    }
}
