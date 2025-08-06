package pn.torn.goldeneye.torn.model.faction.crime;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;

import java.util.List;

/**
 * Torn OC请求
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
public class TornFactionOcDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/faction/crimes";
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(1);
        param.put("cat", List.of("available"));
        return param;
    }
}