package pn.torn.goldeneye.torn.model.user.oc;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;

import java.util.List;

/**
 * Torn用户OC响应参数
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.08.20
 */
@Data
public class TornUserOcVO {
    /**
     * 帮派OC列表
     */
    @JsonProperty("organizedcrimes")
    private List<TornFactionCrimeVO> ocList;
}