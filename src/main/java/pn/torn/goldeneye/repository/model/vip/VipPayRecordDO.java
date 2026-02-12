package pn.torn.goldeneye.repository.model.vip;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * VIP支付记录表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "vip_pay_record", autoResultMap = true)
public class VipPayRecordDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 日志ID
     */
    private String logId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * QQ号
     */
    private Long qqId;
    /**
     * 物品ID
     */
    private Integer itemId;
    /**
     * 物品数量
     */
    private Integer itemQty;
    /**
     * 剩余可兑换数量
     */
    private Integer remainQty;
    /**
     * 日志时间
     */
    private LocalDateTime logTime;
}