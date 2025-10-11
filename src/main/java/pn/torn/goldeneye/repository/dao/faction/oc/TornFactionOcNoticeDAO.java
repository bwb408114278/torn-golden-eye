package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcNoticeMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;

/**
 * Torn Oc跳过持久层类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.30
 */
@Repository
public class TornFactionOcNoticeDAO extends ServiceImpl<TornFactionOcNoticeMapper, TornFactionOcNoticeDO> {
}