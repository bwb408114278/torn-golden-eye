package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcCoefficientDO;

/**
 * OC系数数据库访问层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.01
 */
@Mapper
public interface TornSettingOcCoefficientMapper extends BaseMapper<TornSettingOcCoefficientDO> {
}