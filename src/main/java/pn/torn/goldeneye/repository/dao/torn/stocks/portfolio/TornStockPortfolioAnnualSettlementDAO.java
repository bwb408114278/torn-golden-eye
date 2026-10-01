package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockPortfolioAnnualSettlementMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioAnnualSettlementDO;

import java.time.LocalDateTime;

/**
 * Torn股票α正式仓年度结算台账持久层类
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Repository
public class TornStockPortfolioAnnualSettlementDAO
        extends ServiceImpl<TornStockPortfolioAnnualSettlementMapper, TornStockPortfolioAnnualSettlementDO> {

    /**
     * 冲突安全地插入年度结算台账行
     *
     * @param settlement 待插入的年度结算台账行
     * @return 实际插入行数(0表示该年度已存在台账行)
     */
    public int insertIgnoreConflict(TornStockPortfolioAnnualSettlementDO settlement) {
        return baseMapper.insertIgnoreConflict(settlement);
    }

    /**
     * 按业务唯一键查询年度结算台账行
     *
     * @param portfolioCode 组合编码
     * @param settleYear    被结算的自然年
     * @param ruleVersion   结算口径版本
     * @return 台账行;不存在时返回null
     */
    public TornStockPortfolioAnnualSettlementDO selectByBusinessKey(String portfolioCode, Integer settleYear,
                                                                    String ruleVersion) {
        return baseMapper.selectByBusinessKey(portfolioCode, settleYear, ruleVersion);
    }

    /**
     * 查询指定年度之前最近一个已结算的台账行
     *
     * @param portfolioCode 组合编码
     * @param ruleVersion   结算口径版本
     * @param settleYear    被结算的自然年
     * @return 最近的已结算台账行;不存在时返回null
     */
    public TornStockPortfolioAnnualSettlementDO selectLatestSettledBefore(String portfolioCode, String ruleVersion,
                                                                          Integer settleYear) {
        return baseMapper.selectLatestSettledBefore(portfolioCode, ruleVersion, settleYear);
    }

    /**
     * 已结算台账行落库:首次插入,或把既有降级行补齐为已结算
     *
     * @param settlement  已结算台账行(金额与派生字段必须齐全)
     * @param businessNow 业务时间
     * @return 受影响行数(正常为1)
     */
    public int upsertSettled(TornStockPortfolioAnnualSettlementDO settlement, LocalDateTime businessNow) {
        return baseMapper.upsertSettled(settlement, businessNow);
    }

    /**
     * 回写降级或阻断状态,不写任何金额字段;已结算行不会被降级覆盖
     *
     * @param settlement  降级台账行(必须携带主键、状态与原因)
     * @param businessNow 业务时间
     * @return 实际更新行数(0表示台账行已结算或被清除)
     */
    public int updateDegradedById(TornStockPortfolioAnnualSettlementDO settlement, LocalDateTime businessNow) {
        return baseMapper.updateDegradedById(settlement, businessNow);
    }

    /**
     * 回填年度报告通知审计行ID与通知状态快照
     *
     * @param id           台账行主键
     * @param noticeId     通知审计行ID
     * @param noticeStatus 通知状态快照
     * @param businessNow  业务时间
     * @return 实际更新行数
     */
    public int updateNoticeById(Long id, Long noticeId, String noticeStatus, LocalDateTime businessNow) {
        return baseMapper.updateNoticeById(id, noticeId, noticeStatus, businessNow);
    }
}
