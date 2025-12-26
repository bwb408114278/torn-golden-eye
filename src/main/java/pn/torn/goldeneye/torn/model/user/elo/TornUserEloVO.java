package pn.torn.goldeneye.torn.model.user.elo;

import lombok.Data;

import java.util.List;

/**
 * Torn用户Elo响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.24
 */
@Data
public class TornUserEloVO {
    /**
     * 个人状态列表
     */
    private List<TornUserStatsVO> personalstats;
}