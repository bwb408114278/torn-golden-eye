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
 * @version 1.2.12
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

    /**
     * 查询同期OC收益排行榜
     */
    public List<TornFactionOcBenefitRankDO> queryCohortBenefitRanking(OcBenefitRankingQuery query) {
        return baseMapper.queryCohortBenefitRanking(query);
    }

    /**
     * 查询用户个人普通OC收益明细。
     *
     * @param query 个人收益明细查询参数
     * @return 普通收益明细列表
     */
    public List<TornFactionOcBenefitDO> queryPersonalBenefitList(OcBenefitRankingQuery query) {
        return baseMapper.queryPersonalBenefitList(query);
    }
}