package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.util.List;

/**
 * Torn股票组合仓位数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockPortfolioSlotMapper extends BaseMapper<TornStockPortfolioSlotDO> {

    /**
     * 查询指定组合的全部仓位槽位
     *
     * @param portfolioCode 组合编码
     * @return 该组合的全部槽位列表(按槽位序号升序)
     */
    List<TornStockPortfolioSlotDO> selectAllByPortfolioCode(@Param("portfolioCode") String portfolioCode);

    /**
     * 查询指定组合的全部仓位槽位并加行锁(FOR UPDATE),按slot_no升序
     *
     * @param portfolioCode 组合编码
     * @return 锁定的全部槽位列表
     */
    List<TornStockPortfolioSlotDO> selectAllByPortfolioCodeForUpdate(@Param("portfolioCode") String portfolioCode);
}
