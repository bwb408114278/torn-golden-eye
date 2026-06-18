package pn.torn.goldeneye.torn.model.faction.revive;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Torn帮派复活响应
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
@Data
public class TornFactionReviveRespVO {
    /**
     * 复活记录列表
     */
    @JsonProperty("revives")
    private List<TornFactionReviveVO> reviveList;
}
