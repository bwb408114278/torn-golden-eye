package pn.torn.goldeneye.torn.model.faction.ce;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 帮派CE排名响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.03
 */
@Data
public class TornFactionCeRankVO {
    /**
     * CE排名列表
     */
    @JsonProperty("crimeexp")
    private List<Long> crimeExpList;
}