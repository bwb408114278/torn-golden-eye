package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 帮派活跃度热力图数据
 * <p>
 * 格内主数值为平均有效活跃人数，颜色使用平均有效活跃人数的固定 5 档强对比色板，
 * 并按{@code idleRatio}连续暗化。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.21
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class FactionActivityHeatmapVO extends BaseActivityHeatmapVO {
    /**
     * 标题，格式：{@code 帮派名 [factionId] 活跃度热力图}
     */
    private String title;
    /**
     * 副标题第一行：格内含义、颜色含义与覆盖率说明
     */
    private String subtitle;
    /**
     * 7×24 平均有效活跃人数矩阵 [dayOfWeek][hour]（字段名沿用 V2 以减小接线修改）
     */
    private double[][] averageOnlineCount;
    /**
     * 7×24 有效观测采样数矩阵 [dayOfWeek][hour]，0 表示该格无数据
     */
    private int[][] observedSamples;
    /**
     * 7×24 idle-only 占比矩阵 [dayOfWeek][hour]，值域 [0,1]，
     * 计算口径 {@code averageIdleCount / (averageActiveCount + averageIdleCount)}，分母为 0 时为 0；
     * V2 legacy 格无法区分 Idle，固定为 0
     */
    private double[][] idleRatio;

    /**
     * 创建空帮派热力图
     *
     * @param title 标题
     */
    public static FactionActivityHeatmapVO empty(String title) {
        FactionActivityHeatmapVO vo = new FactionActivityHeatmapVO();
        vo.title = title;
        vo.averageOnlineCount = new double[7][24];
        vo.observedSamples = new int[7][24];
        vo.idleRatio = new double[7][24];
        return vo;
    }
}
