package pn.torn.goldeneye.torn.model.user.racing;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn用户赛车列表请求
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Data
public class TornUserRaceDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/user/races";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(2);
        param.put("limit", List.of("1"));
        param.put("sort", List.of("DESC"));
        return param;
    }
}