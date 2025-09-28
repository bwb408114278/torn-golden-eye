package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;

/**
 * Torn物品数据库访问层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Mapper
public interface TornItemsMapper extends BaseMapper<TornItemsDO> {
}