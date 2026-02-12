package pn.torn.goldeneye.repository.model.vip;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * VIP通知表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "vip_notice", autoResultMap = true)
@NoArgsConstructor
public class VipNoticeDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 校验时间
     */
    private LocalDateTime checkTime;
    /**
     * Durg cd
     */
    private Long drugCd;

    public VipNoticeDO(long userId, LocalDateTime checkTime, long drugCd) {
        this.userId = userId;
        this.checkTime = checkTime;
        this.drugCd = drugCd;
    }
}