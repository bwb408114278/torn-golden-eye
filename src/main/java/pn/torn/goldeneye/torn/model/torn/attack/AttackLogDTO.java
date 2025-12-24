package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn攻击日志请求
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
@AllArgsConstructor
public class AttackLogDTO implements TornReqParamV2 {
    /**
     * 日志ID
     */
    private String logId;
    /**
     * 从第几条开始请求
     */
    private int offset;

    @Override
    public String uri() {
        return "/torn/attacklog";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(2);
        param.put("log", List.of(this.logId));
        param.put("offset", List.of(String.valueOf(this.offset)));

        return param;
    }
}