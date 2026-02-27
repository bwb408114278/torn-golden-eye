package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.SysBlockWordDO;

/**
 * 屏蔽词设置数据库访问层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.25
 */
@Mapper
public interface SysBlockWordMapper extends BaseMapper<SysBlockWordDO> {
}