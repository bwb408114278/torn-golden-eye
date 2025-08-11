package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcSlotMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Torn Oc Slot持久层类
 *
 * @author Bai
 * @version 0.1.0
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
     * 通过OC ID列表查询Map
     *
     * @return Key为OC ID
     */
    public Map<Long, List<TornFactionOcSlotDO>> queryMapByOc(Collection<TornFactionOcDO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return Map.of();
        }

        List<TornFactionOcSlotDO> slotList = queryListByOcId(ocList.stream()
                .map(TornFactionOcDO::getId).collect(Collectors.toSet()));

        Map<Long, List<TornFactionOcSlotDO>> resultMap = HashMap.newHashMap(ocList.size());
        ocList.forEach(oc -> resultMap.put(oc.getId(),
                slotList.stream().filter(s -> s.getOcId().equals(oc.getId())).toList()));
        return resultMap;
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
}