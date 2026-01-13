package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.torn.TornAuctionDO;

/**
 * Torn拍卖数据库访问层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Mapper
public interface TornAuctionMapper extends BaseMapper<TornAuctionDO> {
}