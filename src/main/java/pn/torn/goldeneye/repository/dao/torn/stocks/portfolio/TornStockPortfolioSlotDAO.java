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
 * @version 1.2.14
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

    /**
     * 查询指定组合的全部仓位槽位并加行锁(FOR UPDATE),按slot_no升序
     *
     * @param portfolioCode 组合编码
     * @return 锁定的全部槽位列表
     */
    public List<TornStockPortfolioSlotDO> selectAllByPortfolioCodeForUpdate(String portfolioCode) {
        return baseMapper.selectAllByPortfolioCodeForUpdate(portfolioCode);
    }

    /**
     * 冲突安全的批量插入组合槽位。
     * <p>
     * 使用数据库部分唯一索引 {@code uk_stock_portfolio_slot_code_no} 的同一语义
     * {@code (portfolio_code, slot_no) WHERE deleted = 0} 执行 {@code INSERT ... ON CONFLICT DO NOTHING},
     * 作为应用启动补建与Liquibase迁移并发的幂等兜底;返回实际插入行数,冲突行被忽略不影响幂等。
     *
     * @param slots 待插入的槽位列表
     * @return 实际插入行数(0表示全部与已存在有效槽位冲突被忽略)
     */
    public int insertSlotsIgnoreConflict(List<TornStockPortfolioSlotDO> slots) {
        return baseMapper.insertSlotsIgnoreConflict(slots);
    }
}
