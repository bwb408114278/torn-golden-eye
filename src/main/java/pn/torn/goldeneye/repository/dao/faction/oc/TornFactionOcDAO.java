package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;

import java.util.Collection;
import java.util.List;

/**
 * Torn Oc持久层类
 *
 * @author Bai
 * @version 1.3.0
 * @since 2025.07.29
 */
@Repository
public class TornFactionOcDAO extends ServiceImpl<TornFactionOcMapper, TornFactionOcDO> {
    /**
     * 通过ID列表删除
     *
     * @param idList ID列表
     */
    public void deleteByIdList(List<Long> idList) {
        if (CollectionUtils.isEmpty(idList)) {
            return;
        }

        baseMapper.deleteByIdList(idList);
    }

    /**
     * 按OC规划键集合批量查询已完成状态的历史OC记录
     *
     * @param keys OC规划键集合，格式为等级:名称
     * @return 已完成状态的历史OC记录；键为空时返回空集合
     */
    public List<TornFactionOcDO> queryCompletedByOcKeys(Collection<String> keys) {
        if (CollectionUtils.isEmpty(keys)) {
            return List.of();
        }

        return baseMapper.selectCompletedByOcKeys(keys);
    }

    /**
     * 通过ID列表查询列表
     */
    public List<TornFactionOcDO> queryListByIdList(long factionId, Collection<Long> idList) {
        if (CollectionUtils.isEmpty(idList)) {
            return List.of();
        }

        return lambdaQuery()
                .in(TornFactionOcDO::getId, idList)
                .eq(TornFactionOcDO::getFactionId, factionId)
                .list();
    }

    /**
     * 查询招募中的队伍
     */
    public List<TornFactionOcDO> queryRecrutList(long factionId) {
        return lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode())
                .orderByAsc(TornFactionOcDO::getReadyTime)
                .list();
    }

    /**
     * 查询执行中的队伍
     */
    public List<TornFactionOcDO> queryExecutingOc(long factionId) {
        return lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .notIn(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                .list();
    }
}