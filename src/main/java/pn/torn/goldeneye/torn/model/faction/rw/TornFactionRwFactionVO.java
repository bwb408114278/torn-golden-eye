package pn.torn.goldeneye.torn.model.faction.rw;

import lombok.Data;

/**
 * 帮派RW帮派响应参数
 *
 * @author Bai
 * @since 2025.12.25
 * @version 0.4.0
 */
@Data
public class TornFactionRwFactionVO {
    /**
     * 帮派ID
     */
    private long id;
    /**
     * 帮派名称
     */
    private String name;
    /**
     * 分数
     */
    private int score;
    /**
     * chain
     */
    private int chain;
}