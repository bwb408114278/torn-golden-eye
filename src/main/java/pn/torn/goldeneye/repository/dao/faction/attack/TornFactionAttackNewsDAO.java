package pn.torn.goldeneye.repository.dao.faction.attack;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.attack.TornFactionAttackNewsMapper;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackNewsDO;

/**
 * 帮派攻击新闻持久层类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Repository
public class TornFactionAttackNewsDAO extends ServiceImpl<TornFactionAttackNewsMapper, TornFactionAttackNewsDO> {
}