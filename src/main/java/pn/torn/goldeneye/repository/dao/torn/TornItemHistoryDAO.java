package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornItemHistoryMapper;
import pn.torn.goldeneye.repository.model.torn.TornItemHistoryDO;

/**
 * Torn物品历史持久层类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.26
 */
@Repository
public class TornItemHistoryDAO extends ServiceImpl<TornItemHistoryMapper, TornItemHistoryDO> {
}