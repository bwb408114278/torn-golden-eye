package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;

/**
 * OC高阶链关系配置数据访问映射器。
 *
 * <p>负责映射 TornSettingOcChainDO 与对应配置表的基础CRUD操作。</p>
 */
@Mapper
public interface TornSettingOcChainMapper extends BaseMapper<TornSettingOcChainDO> {
}
