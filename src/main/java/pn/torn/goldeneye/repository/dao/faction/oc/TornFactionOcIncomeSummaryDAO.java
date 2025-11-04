package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcIncomeSummaryMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;

/**
 * OC收益汇总持久层类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.04
 */
@Repository
public class TornFactionOcIncomeSummaryDAO
        extends ServiceImpl<TornFactionOcIncomeSummaryMapper, TornFactionOcIncomeSummaryDO> {
}