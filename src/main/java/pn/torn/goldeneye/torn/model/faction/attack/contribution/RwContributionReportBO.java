package pn.torn.goldeneye.torn.model.faction.attack.contribution;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RW贡献榜报告展示模型
 *
 * @param wars      入选的合格场次，按结束时间倒序，最多3场
 * @param rows      聚合后的榜单行，按总分倒序；无上榜成员时为空列表
 * @param buildTime 报告构建时间，用于页脚展示更新时间
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
public record RwContributionReportBO(
        List<RwContributionWarBO> wars,
        List<RwContributionRowBO> rows,
        LocalDateTime buildTime) {
}
