package pn.torn.goldeneye.repository.model.user;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn用户股票屏蔽表
 *
 * <p>一行代表某用户屏蔽的一支股票。被屏蔽股票不进入
 * {@code Stock分红购买} 与 {@code Stock分红追加} 两个指令的候选池与组合计算，
 * 因此不会被推荐买入或卖出，其已投入成本也不计入本次预算。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_user_stock_block", autoResultMap = true)
public class TornUserStockBlockDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * Torn用户ID
     */
    private Long userId;
    /**
     * 被屏蔽的股票ID
     */
    private Integer stocksId;
}
