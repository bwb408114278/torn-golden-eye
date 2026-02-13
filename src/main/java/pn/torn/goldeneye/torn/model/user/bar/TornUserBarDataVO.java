package pn.torn.goldeneye.torn.model.user.bar;

import lombok.Data;

/**
 * 用户条数据VO
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Data
public class TornUserBarDataVO {
    /**
     * 能量
     */
    private TornUserBarNumberVO energy;
    /**
     * 勇气
     */
    private TornUserBarNumberVO nerve;
}