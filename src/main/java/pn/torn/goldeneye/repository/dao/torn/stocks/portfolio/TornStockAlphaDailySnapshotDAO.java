package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockAlphaDailySnapshotMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;

import java.time.LocalDate;
import java.util.List;

/**
 * α策略日线快照持久层。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Repository
public class TornStockAlphaDailySnapshotDAO extends ServiceImpl<TornStockAlphaDailySnapshotMapper, TornStockAlphaDailySnapshotDO> {
    /**
     * 按业务键锁定日线快照。
     *
     * @param stocksId             股票ID
     * @param businessDate         自然日
     * @param stockUniverseVersion 股票池版本
     * @param alphaRuleVersion     α规则版本
     * @return 日线快照
     */
    public TornStockAlphaDailySnapshotDO selectByBusinessKeyForUpdate(Integer stocksId, LocalDate businessDate,
                                                                      String stockUniverseVersion, String alphaRuleVersion) {
        return baseMapper.selectByBusinessKeyForUpdate(stocksId, businessDate, stockUniverseVersion, alphaRuleVersion);
    }

    /**
     * 冲突安全插入日线快照。
     *
     * @param snapshot 待插入快照
     * @return 实际插入行数
     */
    public int insertIgnoreConflict(TornStockAlphaDailySnapshotDO snapshot) {
        return baseMapper.insertIgnoreConflict(snapshot);
    }

    public List<LocalDate> selectCommonValidDates(String stockUniverseVersion, String alphaRuleVersion,
                                                  int memberCount, LocalDate startDate, LocalDate endDate) {
        return baseMapper.selectCommonValidDates(stockUniverseVersion, alphaRuleVersion, memberCount, startDate, endDate);
    }
}
