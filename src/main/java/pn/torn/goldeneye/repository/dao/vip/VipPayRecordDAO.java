package pn.torn.goldeneye.repository.dao.vip;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.vip.VipPayRecordMapper;
import pn.torn.goldeneye.repository.model.vip.VipPayRecordDO;

/**
 * VIP支付记录持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Repository
public class VipPayRecordDAO extends ServiceImpl<VipPayRecordMapper, VipPayRecordDO> {
}