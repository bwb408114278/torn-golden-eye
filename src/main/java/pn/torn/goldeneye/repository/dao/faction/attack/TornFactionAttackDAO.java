package pn.torn.goldeneye.repository.dao.faction.attack;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.attack.TornFactionAttackMapper;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;

/**
 * 帮派攻击记录持久层类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Repository
public class TornFactionAttackDAO extends ServiceImpl<TornFactionAttackMapper, TornFactionAttackDO> {
}