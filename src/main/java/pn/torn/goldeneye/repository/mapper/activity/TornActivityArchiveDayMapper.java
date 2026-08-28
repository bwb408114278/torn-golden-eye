package pn.torn.goldeneye.repository.mapper.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.activity.TornActivityArchiveDayDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn活跃度V3自然日完整归档标记Mapper
 * <p>
 * 提供已归档日期范围查询与marker幂等写入；主键activity_date保证完成状态不重复。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
public interface TornActivityArchiveDayMapper extends BaseMapper<TornActivityArchiveDayDO> {

    /**
     * 查询日期范围（闭区间）内已完成归档的自然日。
     *
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return 已归档的自然日列表，按activity_date升序
     */
    List<LocalDate> selectArchivedDates(@Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate);

    /**
     * 幂等写入归档完成marker，activity_date冲突时忽略。
     *
     * @param activityDate 已完成归档的自然日
     * @return 实际插入行数，已存在时返回0
     */
    int insertIgnoreConflict(@Param("activityDate") LocalDate activityDate);
}
