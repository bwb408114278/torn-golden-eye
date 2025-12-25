package pn.torn.goldeneye.repository.mapper.faction.attack;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;

/**
 * 帮派Rw数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Mapper
public interface TornFactionRwMapper extends BaseMapper<TornFactionRwDO> {
}