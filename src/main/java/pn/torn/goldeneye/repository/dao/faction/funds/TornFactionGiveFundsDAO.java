package pn.torn.goldeneye.repository.dao.faction.funds;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.funds.TornFactionGiveFundsMapper;
import pn.torn.goldeneye.repository.model.faction.funds.TornFactionGiveFundsDO;

/**
 * 帮派取钱记录持久层类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Repository
public class TornFactionGiveFundsDAO extends ServiceImpl<TornFactionGiveFundsMapper, TornFactionGiveFundsDO> {
}