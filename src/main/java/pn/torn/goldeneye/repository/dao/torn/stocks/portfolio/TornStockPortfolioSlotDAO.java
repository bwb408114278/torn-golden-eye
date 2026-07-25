package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockPortfolioSlotMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.util.List;

/**
 * Torn股票组合仓位持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockPortfolioSlotDAO extends ServiceImpl<TornStockPortfolioSlotMapper, TornStockPortfolioSlotDO> {

    /**
     * 查询指定组合的全部仓位槽位,批量获取避免N+1
     *
     * @param portfolioCode 组合编码
     * @return 该组合的全部槽位列表(按槽位序号升序)
     */
    public List<TornStockPortfolioSlotDO> selectAllByPortfolioCode(String portfolioCode) {
        return baseMapper.selectAllByPortfolioCode(portfolioCode);
    }
}
