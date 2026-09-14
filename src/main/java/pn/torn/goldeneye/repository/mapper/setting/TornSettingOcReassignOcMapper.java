package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignOcDO;

/**
 * 帮派大锅饭OC范围行配置数据访问映射器。
 *
 * <p>负责映射 TornSettingOcReassignOcDO 与对应配置表的基础CRUD操作。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Mapper
public interface TornSettingOcReassignOcMapper extends BaseMapper<TornSettingOcReassignOcDO> {
}
