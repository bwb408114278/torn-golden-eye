package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * Torn股票策略轮次记录表
 * <p>
 * 记录每一轮组合决策的执行情况,包括各阶段规则版本、预期与可用股票数、
 * 起止时间与错误信息,作为策略回放与审计的主线索。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_market_round", autoResultMap = true)
public class TornStockMarketRoundDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 轮次时间(本轮决策锚定的bar时间)
     */
    private LocalDateTime roundTime;
    /**
     * 轮次状态(PENDING/RUNNING/SUCCESS/FAILED)
     */
    private String roundStatus;
    /**
     * bar构建规则版本(本轮使用的K线聚合版本)
     */
    private String barBuildVersion;
    /**
     * 特征计算规则版本
     */
    private String featureVersion;
    /**
     * 买入规则版本
     */
    private String buyRuleVersion;
    /**
     * 卖出规则版本
     */
    private String sellRuleVersion;
    /**
     * 仓位分配规则版本
     */
    private String allocationRuleVersion;
    /**
     * 消息通知规则版本
     */
    private String messageRuleVersion;
    /**
     * 预期参与决策的股票数量
     */
    private Integer expectedStockCount;
    /**
     * 实际可用(特征就绪)的股票数量
     */
    private Integer usableStockCount;
    /**
     * 本轮实际开始执行时间
     */
    private LocalDateTime startedAt;
    /**
     * 本轮执行完成时间
     */
    private LocalDateTime completedAt;
    /**
     * 重试次数(执行失败后自动重试的累计次数)
     */
    private Integer attemptCount;
    /**
     * 错误信息(轮次失败时记录的异常摘要)
     */
    private String errorMessage;
}
