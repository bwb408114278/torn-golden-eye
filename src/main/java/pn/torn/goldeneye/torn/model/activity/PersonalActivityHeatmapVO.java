package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.torn.model.activity.grid.ActivityGridLayout;

/**
 * 个人活跃度热力图数据
 * <p>
 * 格内主数值为有效采样中的有效活跃比例 {@code %}，矩阵维度为 {@code rows × cols}（由口径网格决定）。
 * 颜色在比例色板基础上按{@code idleRatio}连续暗化。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.07.21
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class PersonalActivityHeatmapVO extends BaseActivityHeatmapVO {
    /**
     * 标题，格式：{@code 用户名 [userId] 活跃度热力图}
     */
    private String title;
    /**
     * 副标题第一行：覆盖率说明
     */
    private String subtitle;
    /**
     * 有效活跃比例矩阵 [row][col]，值域 [0,1]
     */
    private double[][] activeRate;
    /**
     * 有效观测采样数矩阵 [row][col]，0 表示该格无数据
     */
    private int[][] observedSamples;
    /**
     * idle-only 占比矩阵 [row][col]，值域 [0,1]，
     * 计算口径 {@code idleSamples / (activeSamples + idleSamples)}，分母为 0 时为 0；
     * V2 legacy 格无法区分 Idle，固定为 0
     */
    private double[][] idleRatio;

    /**
     * 创建空个人热力图
     *
     * @param title 标题
     * @param grid  口径网格，决定矩阵维度
     * @return 矩阵已按网格分配的空热力图
     */
    public static PersonalActivityHeatmapVO empty(String title, ActivityGridLayout grid) {
        PersonalActivityHeatmapVO vo = new PersonalActivityHeatmapVO();
        vo.title = title;
        vo.setGrid(grid);
        vo.activeRate = new double[grid.rows()][grid.cols()];
        vo.observedSamples = new int[grid.rows()][grid.cols()];
        vo.idleRatio = new double[grid.rows()][grid.cols()];
        return vo;
    }
}
