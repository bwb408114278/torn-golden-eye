package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornStocksHistoryMapper;
import pn.torn.goldeneye.repository.model.torn.TornStocksHistoryDO;

/**
 * Torn股票历史持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.26
 */
@Repository
public class TornStocksHistoryDAO extends ServiceImpl<TornStocksHistoryMapper, TornStocksHistoryDO> {
}