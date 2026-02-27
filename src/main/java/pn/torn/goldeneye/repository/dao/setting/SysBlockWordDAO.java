package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.SysBlockWordMapper;
import pn.torn.goldeneye.repository.model.setting.SysBlockWordDO;

/**
 * 屏蔽词设置数持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.25
 */
@Repository
public class SysBlockWordDAO extends ServiceImpl<SysBlockWordMapper, SysBlockWordDO> {
}