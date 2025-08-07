package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;

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
}