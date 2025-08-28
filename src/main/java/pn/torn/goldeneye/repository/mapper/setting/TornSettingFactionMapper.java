package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;

/**
 * Torn设置帮派数据库访问层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.28
 */
@Mapper
public interface TornSettingFactionMapper extends BaseMapper<TornSettingFactionDO> {
}