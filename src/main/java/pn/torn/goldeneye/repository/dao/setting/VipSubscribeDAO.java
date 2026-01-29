package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.VipSubscribeMapper;
import pn.torn.goldeneye.repository.model.setting.VipSubscribeDO;

/**
 * VIP订阅持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Repository
public class VipSubscribeDAO extends ServiceImpl<VipSubscribeMapper, VipSubscribeDO> {
}