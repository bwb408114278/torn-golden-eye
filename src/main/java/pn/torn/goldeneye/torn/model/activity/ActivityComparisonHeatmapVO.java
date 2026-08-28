package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 帮派活跃度对比热力图数据
 * <p>
 * 格内显示 {@code A人数/B人数}（双方平均有效活跃人数），背景使用人数差值着色；
 * Idle 不参与比较和色差，副标题注明该口径。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.08
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ActivityComparisonHeatmapVO extends BaseActivityHeatmapVO {
    /**
     * 标题，固定为"帮派活跃度对比"
     */
    private String title;
    /**
     * 副标题第一行：双方名称与"仅对比有效活跃人数"口径说明
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
     * 7×24 帮派A 平均有效活跃人数矩阵 [dayOfWeek][hour]
     */
    private double[][] faction1AverageOnline;
    /**
     * 7×24 帮派B 平均有效活跃人数矩阵 [dayOfWeek][hour]
     */
    private double[][] faction2AverageOnline;
    /**
     * 7×24 共同有效采样标记矩阵 [dayOfWeek][hour]，true 表示双方均有有效观测
     */
    private boolean[][] bothObserved;

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
        vo.faction1AverageOnline = new double[7][24];
        vo.faction2AverageOnline = new double[7][24];
        vo.bothObserved = new boolean[7][24];
        return vo;
    }
}
