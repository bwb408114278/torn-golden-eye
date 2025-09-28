package pn.torn.goldeneye.torn.model.user.stocks;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn用户股票请求
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/user";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(1);
        param.put("selections", List.of("stocks"));
        return param;
    }
}