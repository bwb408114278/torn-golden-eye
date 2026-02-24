package pn.torn.goldeneye.repository.dao.vip;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.vip.VipSubscribeMapper;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;

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