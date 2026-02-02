package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.VipPayRecordDO;

/**
 * VIP支付记录数据库访问层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Mapper
public interface VipPayRecordMapper extends BaseMapper<VipPayRecordDO> {
}