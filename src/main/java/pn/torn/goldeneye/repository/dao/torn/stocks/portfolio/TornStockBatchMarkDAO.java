package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockBatchMarkMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;

import java.util.List;

/**
 * Torn股票批次标记持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockBatchMarkDAO extends ServiceImpl<TornStockBatchMarkMapper, TornStockBatchMarkDO> {

    /**
     * 按批次ID批量查询全部mark,避免逐条查询产生N+1问题
     *
     * @param batchId 批次ID
     * @return 该批次的全部标记列表(按轮次时间升序)
     */
    public List<TornStockBatchMarkDO> selectByBatchId(Long batchId) {
        return baseMapper.selectByBatchId(batchId);
    }
}
