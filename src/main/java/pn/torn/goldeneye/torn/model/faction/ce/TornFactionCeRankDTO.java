package pn.torn.goldeneye.torn.model.faction.ce;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * 帮派CE排名请求
 *
 * @author Bai
 * @version 1.3.10
 * @since 2025.12.03
 */
@Data
public class TornFactionCeRankDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/faction/crimeexp";
    }

    @Override
    public boolean needFactionAccess() {
        return true;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        return new LinkedMultiValueMap<>();
    }
}
