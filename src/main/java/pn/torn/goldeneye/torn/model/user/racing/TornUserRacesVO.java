package pn.torn.goldeneye.torn.model.user.racing;

import lombok.Data;

import java.util.List;

/**
 * Torn用户赛车列表响应参数
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Data
public class TornUserRacesVO {
    /**
     * 赛车列表
     */
    private List<TornRaceDetailVO> races;
}