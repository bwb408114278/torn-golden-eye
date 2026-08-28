package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 个人活跃度热力图数据
 * <p>
 * 格内主数值为有效采样中的有效活跃比例 {@code %}，矩阵维度 [7][24]（周一=0..周日=6）。
 * 颜色在比例色板基础上按{@code idleRatio}连续暗化。
 *
 * @author Bai
 * @version 1.5.0
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
     * 7×24 有效活跃比例矩阵 [dayOfWeek][hour]，值域 [0,1]，dayOfWeek: 0=周一..6=周日
     */
    private double[][] activeRate;
    /**
     * 7×24 有效观测采样数矩阵 [dayOfWeek][hour]，0 表示该格无数据
     */
    private int[][] observedSamples;
    /**
     * 7×24 idle-only 占比矩阵 [dayOfWeek][hour]，值域 [0,1]，
     * 计算口径 {@code idleSamples / (activeSamples + idleSamples)}，分母为 0 时为 0；
     * V2 legacy 格无法区分 Idle，固定为 0
     */
    private double[][] idleRatio;

    /**
     * 创建空个人热力图
     *
     * @param title 标题
     */
    public static PersonalActivityHeatmapVO empty(String title) {
        PersonalActivityHeatmapVO vo = new PersonalActivityHeatmapVO();
        vo.title = title;
        vo.activeRate = new double[7][24];
        vo.observedSamples = new int[7][24];
        vo.idleRatio = new double[7][24];
        return vo;
    }
}
