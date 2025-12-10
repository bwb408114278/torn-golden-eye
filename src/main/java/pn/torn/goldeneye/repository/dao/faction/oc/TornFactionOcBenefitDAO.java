package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcBenefitMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitUserRankDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;

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
     */
    public List<TornFactionOcBenefitRankDO> queryBenefitRanking(OcBenefitRankingQuery query) {
        return baseMapper.queryBenefitRanking(query);
    }

    /**
     * 查询用户OC收益排行榜
     */
    public TornFactionOcBenefitUserRankDO queryBenefitUserRanking(OcBenefitRankingQuery query) {
        return baseMapper.queryBenefitUserRanking(query);
    }
}