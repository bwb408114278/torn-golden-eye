package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;

import java.util.List;

/**
 * Torn股票通知审计持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockNoticeAuditDAO extends ServiceImpl<TornStockNoticeAuditMapper, TornStockNoticeAuditDO> {

    /**
     * 查询待发送通知,批量获取避免N+1
     *
     * @return 待发送通知列表
     */
    public List<TornStockNoticeAuditDO> selectPendingNotices() {
        return baseMapper.selectPendingNotices();
    }
}
