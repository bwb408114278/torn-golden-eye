package pn.torn.goldeneye.torn.model.torn.stocks;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn股票请求
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
public class TornStocksDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/torn";
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