package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcSlotMapper;
import pn.torn.goldeneye.repository.model.faction.oc.OcSuccessRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIdleRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Torn Oc Slot持久层类
 *
 * @author Bai
 * @version 1.2.6
 * @since 2025.07.29
 */
@Repository
public class TornFactionOcSlotDAO extends ServiceImpl<TornFactionOcSlotMapper, TornFactionOcSlotDO> {
    /**
     * 通过OC ID列表查询列表
     */
    public List<TornFactionOcSlotDO> queryListByOc(Collection<TornFactionOcDO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return List.of();
        }

        return queryListByOcId(ocList.stream().map(TornFactionOcDO::getId).collect(Collectors.toSet()));
    }

    /**
     * 通过OC ID列表查询列表
     */
    public List<TornFactionOcSlotDO> queryListByOcId(Collection<Long> ocIdList) {
        if (CollectionUtils.isEmpty(ocIdList)) {
            return List.of();
        }

        return lambdaQuery().in(TornFactionOcSlotDO::getOcId, ocIdList).list();
    }

    /**
     * 查询缺人的岗位列表
     */
    public List<TornFactionOcSlotDO> queryEmptySlotList(Collection<TornFactionOcDO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return List.of();
        }

        Set<Long> ocIdSet = ocList.stream().map(TornFactionOcDO::getId).collect(Collectors.toSet());
        return lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocIdSet)
                .isNull(TornFactionOcSlotDO::getUserId)
                .list();
    }

    /**
     * 查询OC空转排行榜
     */
    public List<TornFactionOcIdleRankDO> queryIdleRanking(LocalDateTime fromDate, LocalDateTime toDate,
                                                          long factionId, int limit) {
        return baseMapper.queryIdleRanking(fromDate, toDate, factionId, limit);
    }

    /**
     * 查询OC成功率排行榜
     *
     * @param factionId 帮派ID，0为所有帮派
     * @param sinceDate 起始日期（近90天）
     * @param sortAsc   是否升序（非酋榜true，欧皇榜false）
     * @param limit     排行限制
     * @return 排行列表
     */
    public List<OcSuccessRankDO> querySuccessRanking(long factionId, LocalDateTime sinceDate,
                                                     boolean sortAsc, int limit) {
        return baseMapper.querySuccessRanking(factionId, sinceDate, sortAsc, limit);
    }
}
