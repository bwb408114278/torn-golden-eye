package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

/**
 * Torn战斗日志数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Mapper
public interface TornAttackLogMapper extends BaseMapper<TornAttackLogDO> {
}