package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornSettingOcMapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Torn设置OC持久层类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.21
 */
@Repository
public class TornSettingOcDAO extends ServiceImpl<TornSettingOcMapper, TornSettingOcDO> {
    /**
     * 获取OC名称Map
     *
     * @return Key为OC名称
     */
    public Map<String, TornSettingOcDO> getNameMap() {
        List<TornSettingOcDO> list = list();
        Map<String, TornSettingOcDO> resultMap = new HashMap<>();
        list.forEach(o -> resultMap.put(o.getOcName(), o));
        return resultMap;
    }
}