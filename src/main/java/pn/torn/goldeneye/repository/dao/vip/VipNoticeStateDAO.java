package pn.torn.goldeneye.repository.dao.vip;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.vip.VipNoticeStateMapper;
import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;

/**
 * VIP提醒状态持久层类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Repository
public class VipNoticeStateDAO extends ServiceImpl<VipNoticeStateMapper, VipNoticeStateDO> {
}