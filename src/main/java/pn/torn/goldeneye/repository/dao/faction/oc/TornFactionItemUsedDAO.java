package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.armory.TornFactionItemUsedMapper;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;

/**
 * 帮派物品使用记录持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Repository
public class TornFactionItemUsedDAO extends ServiceImpl<TornFactionItemUsedMapper, TornFactionItemUsedDO> {
}