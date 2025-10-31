package pn.torn.goldeneye.torn.model.faction.crime;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn OC请求
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.29
 */
@Data
public class TornFactionOcDTO implements TornReqParamV2 {
    /**
     * 是否完成
     */
    private boolean isComplete;

    @Override
    public String uri() {
        return "/faction/crimes";
    }

    @Override
    public boolean needFactionAccess() {
        return true;
    }

    public TornFactionOcDTO(boolean isComplete) {
        this.isComplete = isComplete;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(1);

        if (isComplete) {
            param.put("cat", List.of("completed"));
        } else {
            param.put("cat", List.of("available"));
        }

        return param;
    }
}