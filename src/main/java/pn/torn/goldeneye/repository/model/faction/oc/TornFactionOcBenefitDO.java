package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * Torn OC收益表
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc_benefit", autoResultMap = true)
public class TornFactionOcBenefitDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * OC ID
     */
    private Long ocId;
    /**
     * OC名称
     */
    private String ocName;
    /**
     * OC状态
     */
    private String ocStatus;
    /**
     * OC完成时间
     */
    private LocalDateTime ocFinishTime;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户岗位
     */
    private String userPosition;
    /**
     * 用户成功率
     */
    private Integer userPassRate;
    /**
     * 收益金钱
     */
    private Long benefitMoney;
}