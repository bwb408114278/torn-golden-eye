package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitUserRankDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;

import java.util.List;

/**
 * OC收益数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.09.10
 */
@Mapper
public interface TornFactionOcBenefitMapper extends BaseMapper<TornFactionOcBenefitDO> {
    /**
     * 查询OC收益排行榜
     *
     * @param query 过滤条件
     * @return 排行榜列表
     */
    List<TornFactionOcBenefitRankDO> queryBenefitRanking(@Param("query") OcBenefitRankingQuery query);

    /**
     * 查询用户OC收益排行榜
     *
     * @param query 过滤条件
     * @return 排行榜列表
     */
    TornFactionOcBenefitUserRankDO queryBenefitUserRanking(@Param("query") OcBenefitRankingQuery query);
}