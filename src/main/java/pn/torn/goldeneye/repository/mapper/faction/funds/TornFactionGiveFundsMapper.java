package pn.torn.goldeneye.repository.mapper.faction.funds;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.funds.GiveFundsRankingDO;
import pn.torn.goldeneye.repository.model.faction.funds.TornFactionGiveFundsDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帮派取钱记录数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Mapper
public interface TornFactionGiveFundsMapper extends BaseMapper<TornFactionGiveFundsDO> {
    /**
     * 查询取钱排行榜
     *
     * @param factionId 帮派ID
     * @param fromDate  开始时间
     * @param toDate    结束时间
     * @return 排行榜列表
     */
    List<GiveFundsRankingDO> queryGiveFundsRanking(@Param("factionId") long factionId,
                                                   @Param("fromDate") LocalDateTime fromDate,
                                                   @Param("toDate") LocalDateTime toDate);
}