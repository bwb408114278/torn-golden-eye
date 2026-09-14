package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignFactionDO;

/**
 * 帮派级大锅饭开关配置数据访问映射器。
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 *
 * <p>负责映射 TornSettingOcReassignFactionDO 与对应配置表的基础CRUD操作。</p>
 */
@Mapper
public interface TornSettingOcReassignFactionMapper extends BaseMapper<TornSettingOcReassignFactionDO> {
}
