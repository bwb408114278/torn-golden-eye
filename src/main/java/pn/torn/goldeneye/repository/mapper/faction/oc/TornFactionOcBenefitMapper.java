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
 * @version 1.2.12
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

    /**
     * 查询同期OC收益排行榜
     *
     * @param query 过滤条件
     * @return 排行榜列表
     */
    List<TornFactionOcBenefitRankDO> queryCohortBenefitRanking(@Param("query") OcBenefitRankingQuery query);

    /**
     * 查询用户个人普通OC收益明细。
     *
     * <p>与排行榜共用同一大锅饭普通收益排除规则，确保个人明细与排行榜的日期边界一致。</p>
     *
     * @param query 个人收益明细查询参数，需包含用户ID、时间范围和帮派排除规则
     * @return 普通收益明细列表
     */
    List<TornFactionOcBenefitDO> queryPersonalBenefitList(@Param("query") OcBenefitRankingQuery query);
}