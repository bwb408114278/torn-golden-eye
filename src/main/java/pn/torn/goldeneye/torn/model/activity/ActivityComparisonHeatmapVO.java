package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;

/**
 * 帮派活跃度对比热力图数据
 * <p>
 * 格内显示 {@code A人数/B人数}，背景使用人数差值着色。
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
@Data
public class ActivityComparisonHeatmapVO {
    /**
     * 标题，固定为"帮派活跃度对比"
     */
    private String title;
    /**
     * 副标题，格式：{@code 帮派A [ID] / 帮派B [ID]}
     */
    private String subtitle;
    /**
     * 帮派A ID
     */
    private long faction1Id;
    /**
     * 帮派A 名称
     */
    private String faction1Name;
    /**
     * 帮派B ID
     */
    private long faction2Id;
    /**
     * 帮派B 名称
     */
    private String faction2Name;
    /**
     * 7×24 帮派A 平均在线人数矩阵 [dayOfWeek][hour]
     */
    private double[][] faction1AverageOnline;
    /**
     * 7×24 帮派B 平均在线人数矩阵 [dayOfWeek][hour]
     */
    private double[][] faction2AverageOnline;
    /**
     * 7×24 共同有效采样标记矩阵 [dayOfWeek][hour]，true 表示双方均有有效观测
     */
    private boolean[][] bothObserved;
    /**
     * 查询天数
     */
    private int totalDays;
    /**
     * 数据是否充足（≥7 天）
     */
    private boolean dataSufficient;
    /**
     * 数据不足时的提示信息
     */
    private String insufficientMessage;
    /**
     * 总体覆盖率
     */
    private double coverage;

    /**
     * 创建空对比热力图
     *
     * @param faction1Id   帮派A ID
     * @param faction1Name 帮派A 名称
     * @param faction2Id   帮派B ID
     * @param faction2Name 帮派B 名称
     */
    public static ActivityComparisonHeatmapVO empty(long faction1Id, String faction1Name,
                                                     long faction2Id, String faction2Name) {
        ActivityComparisonHeatmapVO vo = new ActivityComparisonHeatmapVO();
        vo.title = "帮派活跃度对比";
        vo.faction1Id = faction1Id;
        vo.faction1Name = faction1Name;
        vo.faction2Id = faction2Id;
        vo.faction2Name = faction2Name;
        vo.subtitle = faction1Name + " / " + faction2Name;
        vo.faction1AverageOnline = new double[7][24];
        vo.faction2AverageOnline = new double[7][24];
        vo.bothObserved = new boolean[7][24];
        vo.dataSufficient = false;
        return vo;
    }
}
