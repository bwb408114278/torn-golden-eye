package pn.torn.goldeneye.repository.mapper.vip;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;

/**
 * VIP订阅数据库访问层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Mapper
public interface VipSubscribeMapper extends BaseMapper<VipSubscribeDO> {
}