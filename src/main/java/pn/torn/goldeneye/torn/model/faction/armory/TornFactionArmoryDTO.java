package pn.torn.goldeneye.torn.model.faction.armory;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn帮派物资请求
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.30
 */
@Data
@AllArgsConstructor
public class TornFactionArmoryDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/faction";
    }

    @Override
    public boolean needFactionAccess() {
        return true;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> resultMap = new LinkedMultiValueMap<>(1);
        resultMap.put("selections", List.of("boosters,medical,temporary"));
        return resultMap;
    }
}