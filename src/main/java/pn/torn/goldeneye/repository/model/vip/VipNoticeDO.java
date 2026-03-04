package pn.torn.goldeneye.repository.model.vip;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;
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
     * 上次CD校验时间
     */
    private LocalDateTime lastCdCheckTime;
    /**
     * Durg cd
     */
    private Integer drugCd;
    /**
     * 上次Bar校验时间
     */
    private LocalDateTime lastBarCheckTime;
    /**
     * 能量填满
     */
    private Integer energyFull;
    /**
     * 勇气填满
     */
    private Integer nerveFull;
    /**
     * 旅行滞留国外
     */
    private Integer travelAboard;
    /**
     * 上次旅行校验时间
     */
    private LocalDateTime lastTravelCheckTime;
    /**
     * 是否Refill能量
     */
    private Boolean isRefillEnergy;
    /**
     * 上次Refill校验时间
     */
    private LocalDate lastRefillEnergyCheckDate;

    public VipNoticeDO(long userId) {
        this.userId = userId;

        this.drugCd = 0;
        this.energyFull = 0;
        this.nerveFull = 0;
        this.travelAboard = 0;
        this.isRefillEnergy = false;

        LocalDateTime past = LocalDateTime.of(2000, 1, 1, 0, 0);
        this.lastCdCheckTime = past;
        this.lastBarCheckTime = past;
        this.lastTravelCheckTime = past;
        this.lastRefillEnergyCheckDate = past.toLocalDate();
    }
}