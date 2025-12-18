package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornAttackLogMapper;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

/**
 * Torn战斗日志持久层类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Repository
public class TornAttackLogDAO extends ServiceImpl<TornAttackLogMapper, TornAttackLogDO> {
}