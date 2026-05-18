package pn.torn.goldeneye.repository.mapper.vip;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;

/**
 * VIP提醒设置数据库访问层
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Mapper
public interface VipNoticeConfigMapper extends BaseMapper<VipNoticeConfigDO> {
}