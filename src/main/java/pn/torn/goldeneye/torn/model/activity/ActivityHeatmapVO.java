package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;

/**
 * 活跃度热力图数据
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.07
 */
@Data
public class ActivityHeatmapVO {
    /**
     * 热力图标题
     */
    private String title;
    /**
     * 7×24 活跃度矩阵 [dayOfWeek][hour], dayOfWeek: 0=周一..6=周日, hour: 0-23
     */
    private double[][] heatmap;
    /**
     * 是否为帮派模式（true=显示在线人数, false=显示活跃比例）
     */
    private boolean factionMode;
    /**
     * 统计天数
     */
    private int totalDays;
    /**
     * 每小时理论采样次数（15min轮询=4次，30min轮询=2次）
     */
    private int samplesPerHour;
    /**
     * 数据是否充足
     */
    private boolean dataSufficient;
    /**
     * 数据不足时的提示信息
     */
    private String insufficientMessage;
    /**
     * 是否为对比模式
     */
    private boolean compareMode;
    /**
     * 我方帮派名
     */
    private String faction1Name;
    /**
     * 对方帮派名
     */
    private String faction2Name;
    /**
     * 我方帮派ID
     */
    private long userFactionId;
    /**
     * 对方帮派ID
     */
    private long targetFactionId;

    /**
     * 创建空热力图（非对比模式）
     *
     * @param title 标题
     */
    public static ActivityHeatmapVO empty(String title) {
        ActivityHeatmapVO vo = new ActivityHeatmapVO();
        vo.title = title;
        vo.heatmap = new double[7][24];
        vo.dataSufficient = false;
        return vo;
    }

    /**
     * 创建对比模式热力图
     *
     * @param faction1Id   我方帮派ID
     * @param faction1Name 我方帮派名
     * @param faction2Id   对方帮派ID
     * @param faction2Name 对方帮派名
     */
    public static ActivityHeatmapVO forComparison(long faction1Id, String faction1Name,
                                                   long faction2Id, String faction2Name) {
        ActivityHeatmapVO vo = new ActivityHeatmapVO();
        vo.compareMode = true;
        vo.userFactionId = faction1Id;
        vo.faction1Name = faction1Name;
        vo.targetFactionId = faction2Id;
        vo.faction2Name = faction2Name;
        vo.heatmap = new double[7][24];
        vo.dataSufficient = false;
        return vo;
    }
}
