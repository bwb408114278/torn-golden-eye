package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornAuctionMapper;
import pn.torn.goldeneye.repository.model.torn.TornAuctionDO;

/**
 * Torn拍卖持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Repository
public class TornAuctionDAO extends ServiceImpl<TornAuctionMapper, TornAuctionDO> {
}