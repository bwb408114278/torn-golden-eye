package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;

/**
 * 战斗Log统计响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
public class AttackSummaryVO {
    /**
     * 用户ID
     */
    private Long id;
    /**
     * 用户昵称
     */
    private String name;
    /**
     * 击中数
     */
    private int hits;
    /**
     * 失手数
     */
    private int misses;
    /**
     * 造成伤害
     */
    private long damage;
}