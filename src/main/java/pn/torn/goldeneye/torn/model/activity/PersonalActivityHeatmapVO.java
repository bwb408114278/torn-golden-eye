package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;

/**
 * 个人活跃度热力图数据
 * <p>
 * 格内主数值为有效采样中的活跃比例 {@code %}，矩阵维度 [7][24]（周一=0..周日=6）。
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
@Data
public class PersonalActivityHeatmapVO {
    /**
     * 标题，格式：{@code 用户名 [userId] 活跃度热力图}
     */
    private String title;
    /**
     * 7×24 活跃比例矩阵 [dayOfWeek][hour]，值域 [0,1]，dayOfWeek: 0=周一..6=周日
     */
    private double[][] activeRate;
    /**
     * 7×24 有效观测采样数矩阵 [dayOfWeek][hour]，0 表示该格无数据
     */
    private int[][] observedSamples;
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
     * 总体覆盖率（实际 observed 槽数 / 查询窗口理论槽数）
     */
    private double coverage;

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
        vo.dataSufficient = false;
        return vo;
    }
}
