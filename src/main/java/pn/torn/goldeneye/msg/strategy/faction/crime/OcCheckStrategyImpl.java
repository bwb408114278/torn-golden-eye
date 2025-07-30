package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.ManageMsgStrategy;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;

import java.util.List;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcCheckStrategyImpl extends ManageMsgStrategy {
    private final TornApi tornApi;
    private final TornFactionOcService ocService;

    @Override
    public String getCommand() {
        return "OC校准";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(TornOcStatusEnum.PLANNING), TornFactionOcVO.class);
        ocService.updateOc(oc.getCrimes());

        oc = tornApi.sendRequest(new TornFactionOcDTO(TornOcStatusEnum.RECRUITING), TornFactionOcVO.class);
        ocService.updateOc(oc.getCrimes());
        return super.buildTextMsg("OC数据校准完成");
    }
}