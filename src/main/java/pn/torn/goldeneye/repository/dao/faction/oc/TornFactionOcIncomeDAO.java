package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcIncomeMapper;
import pn.torn.goldeneye.repository.model.faction.oc.OcIncomeYieldStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;

import java.util.List;

/**
 * OC收益分配记录持久层类
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.11.03
 */
@Repository
public class TornFactionOcIncomeDAO extends ServiceImpl<TornFactionOcIncomeMapper, TornFactionOcIncomeDO> {
    /**
     * 查询指定帮派按OC名称与等级聚合的实测产出统计。
     *
     * @param factionId 帮派ID
     * @return 每个（OC名称，等级）的实测产出统计
     */
    public List<OcIncomeYieldStatsDO> queryYieldStats(long factionId) {
        return baseMapper.queryYieldStats(factionId);
    }
}