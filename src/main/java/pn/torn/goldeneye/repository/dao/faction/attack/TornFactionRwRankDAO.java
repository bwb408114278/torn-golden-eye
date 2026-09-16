package pn.torn.goldeneye.repository.dao.faction.attack;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.attack.TornFactionRwRankMapper;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;

/**
 * RW真赛战神榜名次结算持久层类
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Repository
public class TornFactionRwRankDAO extends ServiceImpl<TornFactionRwRankMapper, TornFactionRwRankDO> {
    /**
     * 物理删除指定RW的结算行
     *
     * @param rwId RW ID
     * @return 实际删除行数
     */
    public int physicalDeleteByRwId(long rwId) {
        return baseMapper.physicalDeleteByRwId(rwId);
    }
}
