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
 * @version 1.2.14
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

    /**
     * 冲突安全的批量插入组合槽位。
     * <p>
     * 批量执行 {@code INSERT ... ON CONFLICT (portfolio_code, slot_no) WHERE deleted = 0 DO NOTHING},
     * 与数据库部分唯一索引 {@code uk_stock_portfolio_slot_code_no} 语义一致: 与已有有效槽位冲突的行被
     * 静默忽略,不抛重复键异常。返回实际插入行数,用于启动补建判断本次真正新增数量,与Liquibase迁移
     * 并发执行时自动收敛,不重复、不失败。
     *
     * @param slots 待插入的槽位列表
     * @return 实际插入行数(重复冲突行不计入)
     */
    int insertSlotsIgnoreConflict(@Param("slots") List<TornStockPortfolioSlotDO> slots);
}
