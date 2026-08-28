package pn.torn.goldeneye.repository.dao.activity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.activity.TornActivityUserDailyMapper;
import pn.torn.goldeneye.repository.model.activity.TornActivityUserDailyDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn活跃度V3用户日终压缩归档持久层类
 * <p>
 * 隔离Mapper调用；归档服务不得直接依赖Mapper。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Repository
public class TornActivityUserDailyDAO extends ServiceImpl<TornActivityUserDailyMapper, TornActivityUserDailyDO> {

    /**
     * 批量UPSERT用户日包，业务唯一键冲突时覆盖为同日期完整V3包。
     * <p>
     * 自定义XML不经过MyBatis-Plus主键自动填充，缺失ID的记录在此统一以雪花ID补齐，
     * 此处是XML写入主键补齐的唯一位置；单批SQL短事务提交。
     *
     * @param list 用户日包列表，空集合直接返回0
     * @return 实际写入行数（INSERT与UPDATE均计入）
     */
    @Transactional
    public int upsertBatch(List<TornActivityUserDailyDO> list) {
        if (CollectionUtils.isEmpty(list)) {
            return 0;
        }
        list.stream()
                .filter(item -> item.getId() == null)
                .forEach(item -> item.setId(IdWorker.getId()));
        return baseMapper.upsertBatch(list);
    }

    /**
     * 按用户与日期范围（闭区间）读取日包。
     *
     * @param userId    Torn用户ID
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return 命中范围的用户日包列表，按activity_date升序，无数据时返回空列表
     */
    public List<TornActivityUserDailyDO> selectByUserAndDateRange(long userId,
                                                                  LocalDate startDate, LocalDate endDate) {
        return baseMapper.selectByUserAndDateRange(userId, startDate, endDate);
    }
}
