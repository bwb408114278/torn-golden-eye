package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;

/**
 * Torn设置OC数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Mapper
public interface TornSettingOcMapper extends BaseMapper<TornSettingOcDO> {
}