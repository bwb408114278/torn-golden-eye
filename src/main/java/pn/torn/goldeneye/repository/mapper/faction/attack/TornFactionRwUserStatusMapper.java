package pn.torn.goldeneye.repository.mapper.faction.attack;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwUserStatusDO;

/**
 * 帮派Rw用户状态数据库访问层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.21
 */
@Mapper
public interface TornFactionRwUserStatusMapper extends BaseMapper<TornFactionRwUserStatusDO> {
}