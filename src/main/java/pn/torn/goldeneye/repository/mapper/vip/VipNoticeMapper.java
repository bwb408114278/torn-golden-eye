package pn.torn.goldeneye.repository.mapper.vip;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;

/**
 * VIP通知数据库访问层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Mapper
public interface VipNoticeMapper extends BaseMapper<VipNoticeDO> {
}