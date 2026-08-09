package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockBatchMarkMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票批次标记持久层类
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.24
 */
@Repository
public class TornStockBatchMarkDAO extends ServiceImpl<TornStockBatchMarkMapper, TornStockBatchMarkDO> {

    /**
     * 查询摘要日内动态SELL研究mark(关联活跃或当日动作的正式/候选影子批次)。
     * <p>
     * 供日报展示动态SELL研究状态,以{@code torn_stock_batch_mark}为唯一数据源。
     *
     * @param startTime 摘要日期起点(含)
     * @param endTime   摘要日期终点(不含)
     * @return 研究mark列表
     */
    public List<TornStockBatchMarkDO> selectDynamicShadowResearchMarks(LocalDateTime startTime,
                                                                       LocalDateTime endTime) {
        return baseMapper.selectDynamicShadowResearchMarks(startTime, endTime);
    }
}
