package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcBenefitMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OC收益持久层类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.09.09
 */
@Repository
public class TornFactionOcBenefitDAO extends ServiceImpl<TornFactionOcBenefitMapper, TornFactionOcBenefitDO> {
    /**
     * 查询OC收益排行榜
     *
     * @param factionId 帮派ID
     * @param fromDate  开始时间
     * @param toDate    结束时间
     * @return 排行榜列表
     */
    public List<TornFactionOcBenefitRankDO> queryBenefitRanking(long factionId,
                                                                LocalDateTime fromDate, LocalDateTime toDate) {
        return baseMapper.queryBenefitRanking(factionId, fromDate, toDate);
    }

    /**
     * 查询OC大锅饭收益排行榜
     *
     * @param factionId    帮派ID
     * @param fromDate     开始时间
     * @param toDate       结束时间
     * @param reassignList 大锅饭OC名称列表
     * @param yearMonth    年月
     * @return 排行榜列表
     */
    public List<TornFactionOcBenefitRankDO> queryIncomeRanking(long factionId,
                                                               LocalDateTime fromDate, LocalDateTime toDate,
                                                               List<String> reassignList, String yearMonth) {
        return baseMapper.queryIncomeRanking(factionId, fromDate, toDate, reassignList, yearMonth);
    }

    /**
     * 查询全帮派OC收益排行榜
     *
     * @param yearMonth           年月
     * @param fromDate            开始时间
     * @param toDate              结束时间
     * @param reassignFactionList 大锅饭帮派列表
     * @param reassignList        大锅饭OC名称列表
     * @return 排行榜列表
     */
    public List<TornFactionOcBenefitRankDO> queryAllBenefitRanking(String yearMonth,
                                                                   LocalDateTime fromDate, LocalDateTime toDate,
                                                                   List<Long> reassignFactionList,
                                                                   List<String> reassignList) {
        return baseMapper.queryAllBenefitRanking(yearMonth, fromDate, toDate, reassignFactionList, reassignList);
    }
}