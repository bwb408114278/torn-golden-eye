package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;

import java.time.LocalDate;
import java.util.List;

/**
 * α策略日线快照数据库访问层。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Mapper
public interface TornStockAlphaDailySnapshotMapper extends BaseMapper<TornStockAlphaDailySnapshotDO> {
    /**
     * 按稳定业务键锁定日线快照。
     *
     * @param stocksId             股票ID
     * @param businessDate         自然日
     * @param stockUniverseVersion 股票池版本
     * @param alphaRuleVersion     α规则版本
     * @return 日线快照
     */
    TornStockAlphaDailySnapshotDO selectByBusinessKeyForUpdate(@Param("stocksId") Integer stocksId,
                                                               @Param("businessDate") LocalDate businessDate,
                                                               @Param("stockUniverseVersion") String stockUniverseVersion,
                                                               @Param("alphaRuleVersion") String alphaRuleVersion);

    /**
     * 按业务键冲突安全插入日线快照。
     *
     * @param snapshot 待插入快照
     * @return 实际插入行数
     */
    int insertIgnoreConflict(@Param("snapshot") TornStockAlphaDailySnapshotDO snapshot);

    /**
     * 按业务键冲突安全批量插入日线快照。
     * <p>
     * 调用方必须把单批条数控制在合理范围(建议500条以内),避免单次SQL参数过多。
     *
     * @param snapshots 待插入日线快照
     * @return 实际生效行数
     */
    int batchInsertIgnoreConflict(@Param("snapshots") List<TornStockAlphaDailySnapshotDO> snapshots);

    /**
     * 查询指定版本日期范围内的日线快照。
     *
     * @param stockUniverseVersion 股票池版本
     * @param alphaRuleVersion     α规则版本
     * @param startDate            起始日期
     * @param endDate              结束日期
     * @return 日线快照
     */
    List<TornStockAlphaDailySnapshotDO> selectByDateRange(@Param("stockUniverseVersion") String stockUniverseVersion,
                                                          @Param("alphaRuleVersion") String alphaRuleVersion,
                                                          @Param("startDate") LocalDate startDate,
                                                          @Param("endDate") LocalDate endDate);

    /**
     * 查询固定股票池全部成员均合法收盘的共同有效自然日。
     * <p>
     * 口径与Java侧"成员集合与固定股票池完全相等"一致:只统计全部成员都有合法收盘的自然日,
     * 结果数量只代表共同有效日数量,不代表连续自然日覆盖,无行情自然日不会出现。
     *
     * @param stockUniverseVersion 股票池版本
     * @param alphaRuleVersion     α规则版本
     * @param memberCount          股票池成员数量
     * @param memberIds            股票池成员ID
     * @param startDate            起始日期
     * @param endDate              结束日期
     * @return 升序共同有效日期
     */
    List<LocalDate> selectCommonValidDates(@Param("stockUniverseVersion") String stockUniverseVersion,
                                           @Param("alphaRuleVersion") String alphaRuleVersion,
                                           @Param("memberCount") int memberCount,
                                           @Param("memberIds") List<Integer> memberIds,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);
}
