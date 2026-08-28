package pn.torn.goldeneye.torn.model.activity;

import lombok.Data;

/**
 * 活跃度热力图三种图片的共同元数据
 * <p>
 * 收敛个人图、帮派图和对比图共用的查询范围、覆盖率与数据可用性字段。
 * V3废止"少于7天拒绝整张图"：只要范围内存在至少一个有效observed槽即{@code hasData=true}出图，
 * 部分覆盖与legacy提示放入{@code noticeMessage}由副标题第二行表达。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Data
public class BaseActivityHeatmapVO {
    /**
     * 查询范围自然日总数（闭区间）
     */
    private int totalDays;
    /**
     * 总体覆盖率（实际 observed 槽数 / 查询窗口理论槽数）
     */
    private double coverage;
    /**
     * 范围内是否存在至少一个有效 observed 槽；false 时指令端仅返回无数据文本
     */
    private boolean hasData;
    /**
     * 副标题第二行提示（部分覆盖采样日/星期不足与 legacy 拼接），无提示时为 null
     */
    private String noticeMessage;
    /**
     * 查询结果是否包含 V2 legacy 采样（Idle 占比未知，强制 idleRatio=0）
     */
    private boolean legacyDataIncluded;
}
