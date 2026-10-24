package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornSettingOcFlowNodeMapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcFlowNodeDO;

/**
 * OC流程图节点配置数据访问层。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@Repository
public class TornSettingOcFlowNodeDAO
        extends ServiceImpl<TornSettingOcFlowNodeMapper, TornSettingOcFlowNodeDO> {
}
