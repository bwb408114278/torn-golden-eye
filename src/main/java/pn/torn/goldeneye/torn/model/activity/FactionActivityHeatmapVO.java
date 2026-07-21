package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;

/**
 * 帮派活跃度热力图数据
 * <p>
 * 格内主数值为平均在线人数，背景颜色使用在线成员比例（{@code onlineRatio}）。
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
@Data
public class FactionActivityHeatmapVO {
    /**
     * 标题，格式：{@code 帮派名 [factionId] 活跃度热力图}
     */
    private String title;
    /**
     * 副标题，说明格内含义和颜色含义
     */
    private String subtitle;
    /**
     * 7×24 平均在线人数矩阵 [dayOfWeek][hour]
     */
    private double[][] averageOnlineCount;
    /**
     * 7×24 在线成员比例矩阵 [dayOfWeek][hour]，值域 [0,1]，用于背景着色
     */
    private double[][] onlineRatio;
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
     * 总体覆盖率
     */
    private double coverage;

    /**
     * 创建空帮派热力图
     *
     * @param title 标题
     */
    public static FactionActivityHeatmapVO empty(String title) {
        FactionActivityHeatmapVO vo = new FactionActivityHeatmapVO();
        vo.title = title;
        vo.averageOnlineCount = new double[7][24];
        vo.onlineRatio = new double[7][24];
        vo.observedSamples = new int[7][24];
        vo.dataSufficient = false;
        return vo;
    }
}
