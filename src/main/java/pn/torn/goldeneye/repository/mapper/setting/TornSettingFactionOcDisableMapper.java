package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcDisableDO;

/**
 * Torn设置帮派OC禁用数据库访问层
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.04.27
 */
@Mapper
public interface TornSettingFactionOcDisableMapper extends BaseMapper<TornSettingFactionOcDisableDO> {
}