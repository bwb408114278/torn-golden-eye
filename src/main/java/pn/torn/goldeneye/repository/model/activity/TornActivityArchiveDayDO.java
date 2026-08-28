package pn.torn.goldeneye.repository.model.activity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Torn活跃度V3自然日完整归档完成标记表
 * <p>
 * 仅表达某V3自然日的用户日包和帮派日包均已成功批量UPSERT，
 * 不是运行日志或任务平台，因此不继承{@code BaseDO}审计字段；
 * {@code activityDate}为主键，保证完成状态不重复。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Data
@TableName("torn_activity_archive_day")
public class TornActivityArchiveDayDO {
    /**
     * 已完成归档的Asia/Shanghai自然日（主键）
     */
    private LocalDate activityDate;
    /**
     * 数据版本，固定V3
     */
    private String dataVersion;
    /**
     * 归档完成的本地审计时间
     */
    private LocalDateTime archivedAt;
}
