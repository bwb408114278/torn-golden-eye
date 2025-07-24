package pn.torn.goldeneye.repository.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

/**
 * Torn User 数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Mapper
public interface TornUserMapper extends BaseMapper<TornUserDO> {
}