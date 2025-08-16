package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.setting.SysSettingDO;

/**
 * 系统设置数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.08
 */
@Mapper
public interface SysSettingMapper extends BaseMapper<SysSettingDO> {
    /**
     * 通过Key删除
     *
     * @param key 设置Key
     */
    void deleteByKey(@Param("key") String key);
}