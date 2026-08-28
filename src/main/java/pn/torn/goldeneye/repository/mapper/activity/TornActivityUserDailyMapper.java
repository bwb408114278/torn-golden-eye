package pn.torn.goldeneye.repository.mapper.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.activity.TornActivityUserDailyDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn活跃度V3用户日终压缩归档Mapper
 * <p>
 * 提供用户日包的PostgreSQL原子批量UPSERT与按用户日期范围的显式字段读取；
 * 冲突目标必须与唯一索引{@code uk_activity_user_daily_user_date}逐字一致。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
public interface TornActivityUserDailyMapper extends BaseMapper<TornActivityUserDailyDO> {

    /**
     * 批量UPSERT用户日包，业务唯一键(user_id, activity_date)冲突时覆盖为同日期完整V3包。
     *
     * @param list 用户日包列表，主键已由DAO以雪花ID补齐
     * @return 实际写入行数（INSERT与UPDATE均计入）
     */
    int upsertBatch(@Param("list") List<TornActivityUserDailyDO> list);

    /**
     * 按用户与日期范围（闭区间）读取日包，按activity_date升序返回。
     *
     * @param userId    Torn用户ID
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return 命中范围的用户日包列表，无数据时返回空列表
     */
    List<TornActivityUserDailyDO> selectByUserAndDateRange(@Param("userId") long userId,
                                                           @Param("startDate") LocalDate startDate,
                                                           @Param("endDate") LocalDate endDate);
}
