package pn.torn.goldeneye.repository.model.vip;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.BaseDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

import java.time.LocalDate;

/**
 * VIP订阅表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "vip_subscribe", autoResultMap = true)
@NoArgsConstructor
public class VipSubscribeDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * QQ号
     */
    private Long qqId;
    /**
     * 订阅时长
     */
    private Integer subscribeLength;
    /**
     * 起始日期
     */
    private LocalDate startDate;
    /**
     * 结束日期
     */
    private LocalDate endDate;

    public VipSubscribeDO(TornUserDO user, int length) {
        this.userId = user.getId();
        this.qqId = user.getQqId();
        this.subscribeLength = length;
    }
}