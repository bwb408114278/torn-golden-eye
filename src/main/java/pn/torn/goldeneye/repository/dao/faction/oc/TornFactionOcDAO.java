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
 * @version 0.1.0
 * @since 2025.07.29
 */
@Repository
public class TornFactionOcDAO extends ServiceImpl<TornFactionOcMapper, TornFactionOcDO> {
    /**
     * 更新OC为已完成
     *
     * @param id OC ID
     */
    public void updateCompleted(long id) {
        lambdaUpdate()
                .set(TornFactionOcDO::getStatus, TornOcStatusEnum.COMPLETED.getCode())
                .set(TornFactionOcDO::isHasCurrent, false)
                .eq(TornFactionOcDO::getId, id)
                .update();
    }

    /**
     * 查询轮转队中的执行队
     */
    public List<TornFactionOcDO> queryRotationExecList() {
        return lambdaQuery()
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.PLANNING.getCode())
                .eq(TornFactionOcDO::isHasCurrent, true)
                .list();
    }

    /**
     * 通过ID列表查询列表
     */
    public List<TornFactionOcDO> queryListByIdList(Collection<Long> idList) {
        if (CollectionUtils.isEmpty(idList)) {
            return List.of();
        }

        return lambdaQuery().in(TornFactionOcDO::getId, idList).list();
    }

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
}