package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import pn.torn.goldeneye.torn.service.stocks.alert.StockReplayBoundary;

/**
 * 回放摘要。
 *
 * @param runId                 运行标识
 * @param portfolioId           组合标识
 * @param status                运行状态
 * @param inputStartTime        输入开始时间
 * @param inputEndTime          输入结束时间
 * @param barBuildVersion       bar版本
 * @param featureVersion        特征版本
 * @param buyRuleVersion        买入规则版本
 * @param sellRuleVersion       卖出规则版本
 * @param allocationRuleVersion 分配规则版本
 * @param messageRuleVersion    消息规则版本
 * @param tracks                轨道
 * @param initialCash           初始资金
 * @param finalEquity           最终权益
 * @param returnRate            收益率
 * @param maxDrawdown           最大回撤
 * @param slotUtilization       槽位利用率
 * @param tradeCount            交易数
 * @param rejectionCount        拒绝数
 * @param dataStatus            数据状态
 * @param errors                错误信息
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplaySummary(String runId, String portfolioId, String status,
                                 String inputStartTime, String inputEndTime,
                                 String barBuildVersion, String featureVersion,
                                 String buyRuleVersion, String sellRuleVersion,
                                 String allocationRuleVersion, String messageRuleVersion,
                                 String tracks, String initialCash, String finalEquity,
                                 String returnRate, String maxDrawdown, String slotUtilization,
                                 int tradeCount, int rejectionCount, String dataStatus,
                                 String errors) {
    /**
     * 创建空结果的完成摘要。
     *
     * @param boundary   回放边界
     * @param tradeCount 交易数
     * @return 完成摘要
     */
    public static StockReplaySummary completed(StockReplayBoundary boundary, int tradeCount) {
        return new StockReplaySummary(boundary.runId(), boundary.portfolioId(), "COMPLETED",
                "", "", "", "", "", "", "", "", "", "0", "0", "0", "0", "0",
                tradeCount, 0, "COMPLETE", "");
    }

    /**
     * 根据显式回放请求创建摘要。
     *
     * @param request        回放请求
     * @param boundary       回放边界
     * @param tradeCount     交易数
     * @param rejectionCount 拒绝数
     * @param finalEquity    最终权益
     * @param dataStatus     数据状态
     * @param errors         错误信息
     * @return 回放摘要
     */
    public static StockReplaySummary fromRequest(StockReplayRequest request, StockReplayBoundary boundary,
                                                 int tradeCount, int rejectionCount, String finalEquity,
                                                 String dataStatus, String errors) {
        String tracks = request.tracks().stream().map(StockReplayTrackEnum::getCode).sorted()
                .reduce((left, right) -> left + "|" + right).orElse("");
        String initialCash = request.tracks().contains(StockReplayTrackEnum.FORMAL_5_SLOT)
                ? StockReplayRequest.FORMAL_SLOT_INITIAL_CASH
                .multiply(java.math.BigDecimal.valueOf(StockReplayRequest.FORMAL_SLOT_COUNT)).toPlainString() : "";
        return new StockReplaySummary(boundary.runId(), boundary.portfolioId(), "COMPLETED",
                request.startTime().toString(), request.endTime().toString(), request.barBuildVersion(),
                request.featureVersion(), request.buyRuleVersion(), request.sellRuleVersion(),
                request.allocationRuleVersion(), request.messageRuleVersion(), tracks, initialCash,
                finalEquity, "", "", "", tradeCount, rejectionCount, dataStatus, errors);
    }
}
