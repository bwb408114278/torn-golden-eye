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
     * 查询共同有效日期。
     *
     * @param stockUniverseVersion 股票池版本
     * @param alphaRuleVersion     α规则版本
     * @param memberCount          股票池成员数量
     * @param startDate            起始日期
     * @param endDate              结束日期
     * @return 共同有效日期
     */
    List<LocalDate> selectCommonValidDates(@Param("stockUniverseVersion") String stockUniverseVersion,
                                           @Param("alphaRuleVersion") String alphaRuleVersion,
                                           @Param("memberCount") int memberCount,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);
}
