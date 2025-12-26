package pn.torn.goldeneye.torn.model.faction.rw;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 帮派RW响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Data
public class TornFactionRwRespVO {
    /**
     * 战斗列表
     */
    @JsonProperty("rankedwars")
    private List<TornFactionRwVO> rwList;
}