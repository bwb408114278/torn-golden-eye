package pn.torn.goldeneye.repository.mapper.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.activity.TornActivityFactionDailyDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn活跃度V3帮派日终压缩归档Mapper
 * <p>
 * 提供帮派日包的PostgreSQL原子批量UPSERT与按帮派日期范围的显式字段读取；
 * 冲突目标必须与唯一索引{@code uk_activity_faction_daily_faction_date}逐字一致。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
public interface TornActivityFactionDailyMapper extends BaseMapper<TornActivityFactionDailyDO> {

    /**
     * 批量UPSERT帮派日包，业务唯一键(faction_id, activity_date)冲突时覆盖为同日期完整V3包。
     *
     * @param list 帮派日包列表，主键已由DAO以雪花ID补齐
     * @return 实际写入行数（INSERT与UPDATE均计入）
     */
    int upsertBatch(@Param("list") List<TornActivityFactionDailyDO> list);

    /**
     * 按帮派与日期范围（闭区间）读取日包，按activity_date升序返回。
     *
     * @param factionId Torn帮派ID
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return 命中范围的帮派日包列表，无数据时返回空列表
     */
    List<TornActivityFactionDailyDO> selectByFactionAndDateRange(@Param("factionId") long factionId,
                                                                 @Param("startDate") LocalDate startDate,
                                                                 @Param("endDate") LocalDate endDate);
}
