package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;

/**
 * Torn股票组合仓位表
 * <p>
 * 每个组合由若干仓位(slot)组成,每个仓位独立管理初始资金、可用资金、
 * 预留资金与当前持仓批次,实现资金隔离与并发安全的仓位分配。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_portfolio_slot", autoResultMap = true)
public class TornStockPortfolioSlotDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 组合编码(标识仓位所属的组合)
     */
    private String portfolioCode;
    /**
     * 仓位序号(组合内的槽位编号,从1开始)
     */
    private Integer slotNo;
    /**
     * 初始资金(仓位创建时分配的资金额度)
     */
    private BigDecimal initialCash;
    /**
     * 可用资金(扣除在途与冻结后可立即使用的资金)
     */
    private BigDecimal availableCash;
    /**
     * 预留资金(为待入场批次锁定的资金,不可挪用)
     */
    private BigDecimal reservedCash;
    /**
     * 当前持仓批次ID(仓位正在持有的虚拟批次ID,空仓时为空)
     */
    private Long currentBatchId;
    /**
     * 仓位状态(IDLE空闲/PENDING待入场/HOLDING持仓/CLOSING平仓中)
     */
    private String slotStatus;
    /**
     * 乐观锁版本号(并发更新仓位资金时用于冲突检测)
     */
    private Long lockVersion;
}
