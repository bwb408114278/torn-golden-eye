package pn.torn.goldeneye.repository.dao.activity;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.mapper.activity.TornActivityArchiveDayMapper;
import pn.torn.goldeneye.repository.model.activity.TornActivityArchiveDayDO;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Torn活跃度V3自然日完整归档标记持久层类
 * <p>
 * 隔离Mapper调用；marker只能在用户日包和帮派日包都成功批量UPSERT后写入。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Repository
public class TornActivityArchiveDayDAO extends ServiceImpl<TornActivityArchiveDayMapper, TornActivityArchiveDayDO> {

    /**
     * 查询日期范围（闭区间）内已完成归档的自然日集合。
     *
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return 已归档自然日集合，无数据时返回空集合
     */
    public Set<LocalDate> selectArchivedDates(LocalDate startDate, LocalDate endDate) {
        return new HashSet<>(baseMapper.selectArchivedDates(startDate, endDate));
    }

    /**
     * 幂等写入归档完成marker，独立短事务提交。
     *
     * @param activityDate 已完成归档的自然日
     */
    @Transactional
    public void insertMarker(LocalDate activityDate) {
        baseMapper.insertIgnoreConflict(activityDate);
    }
}
