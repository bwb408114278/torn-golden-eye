package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcIncomeMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;

/**
 * OC收益分配记录持久层类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Repository
public class TornFactionOcIncomeDAO extends ServiceImpl<TornFactionOcIncomeMapper, TornFactionOcIncomeDO> {
}