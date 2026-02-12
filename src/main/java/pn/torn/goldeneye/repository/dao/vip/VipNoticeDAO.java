package pn.torn.goldeneye.repository.dao.vip;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.vip.VipNoticeMapper;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;

/**
 * VIP提醒持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Repository
public class VipNoticeDAO extends ServiceImpl<VipNoticeMapper, VipNoticeDO> {
}