package pn.torn.goldeneye.repository.dao.faction.funds;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.funds.TornFactionGiveFundsMapper;
import pn.torn.goldeneye.repository.model.faction.funds.GiveFundsRankingDO;
import pn.torn.goldeneye.repository.model.faction.funds.TornFactionGiveFundsDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帮派取钱记录持久层类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Repository
public class TornFactionGiveFundsDAO extends ServiceImpl<TornFactionGiveFundsMapper, TornFactionGiveFundsDO> {
    /**
     * 查询取钱排行榜
     *
     * @param factionId 帮派ID
     * @param fromDate  开始时间
     * @param toDate    结束时间
     * @return 排行榜列表
     */
    public List<GiveFundsRankingDO> queryGiveFundsRanking(long factionId, LocalDateTime fromDate, LocalDateTime toDate) {
        return baseMapper.queryGiveFundsRanking(factionId, fromDate, toDate);
    }
}