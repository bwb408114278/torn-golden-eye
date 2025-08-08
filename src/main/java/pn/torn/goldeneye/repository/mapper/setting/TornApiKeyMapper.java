package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

/**
 * Torn Api Key数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Mapper
public interface TornApiKeyMapper extends BaseMapper<TornApiKeyDO> {
}