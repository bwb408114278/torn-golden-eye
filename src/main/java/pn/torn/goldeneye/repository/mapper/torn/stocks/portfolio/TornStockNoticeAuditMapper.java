package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;

import java.util.List;

/**
 * Torn股票通知审计数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockNoticeAuditMapper extends BaseMapper<TornStockNoticeAuditDO> {

    /**
     * 查询待发送通知
     *
     * @return 待发送通知列表
     */
    List<TornStockNoticeAuditDO> selectPendingNotices();
}
