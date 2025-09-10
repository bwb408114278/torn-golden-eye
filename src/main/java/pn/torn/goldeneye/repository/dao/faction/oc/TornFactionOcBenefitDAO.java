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
 * @version 0.2.0
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
}