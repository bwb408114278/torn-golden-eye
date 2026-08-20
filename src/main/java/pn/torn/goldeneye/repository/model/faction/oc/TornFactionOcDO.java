package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOc;

import java.time.LocalDateTime;

/**
 * Torn OC表
 *
 * @author Bai
 * @version 1.3.6
 * @since 2025.07.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc", autoResultMap = true)
public class TornFactionOcDO extends BaseDO implements TornFactionOc {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 名称
     */
    private String name;
    /**
     * 级别
     */
    private Integer rank;
    /**
     * 状态
     */
    private String status;
    /**
     * 准备时间
     */
    private LocalDateTime readyTime;
    /**
     * 执行时间
     */
    private LocalDateTime executedTime;
    /**
     * 上级OC ID
     */
    private Long previousOcId;
    /**
     * Torn权威创建时间（API created_at映射的外部事实）；本地入库审计时间见BaseDO.createTime，两者不得混用
     */
    private LocalDateTime tornCreatedAt;
    /**
     * 奖励金钱
     */
    private Long rewardMoney;
    /**
     * 奖励物品
     */
    private String rewardItems;
    /**
     * 奖励物品价值
     */
    private String rewardItemsValue;
    /**
     * 是否已通知
     */
    private Boolean hasNoticed;
}
