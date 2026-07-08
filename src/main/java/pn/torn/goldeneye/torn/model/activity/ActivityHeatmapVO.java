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
}
