package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcFlowNodeDO;

/**
 * OC流程图节点配置数据访问映射器。
 *
 * <p>负责映射 TornSettingOcFlowNodeDO 与对应配置表的基础CRUD操作。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@Mapper
public interface TornSettingOcFlowNodeMapper extends BaseMapper<TornSettingOcFlowNodeDO> {
}
