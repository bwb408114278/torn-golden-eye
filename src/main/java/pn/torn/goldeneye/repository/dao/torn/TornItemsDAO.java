package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornItemsMapper;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;

/**
 * Torn物品持久层类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Repository
public class TornItemsDAO extends ServiceImpl<TornItemsMapper, TornItemsDO> {
}