package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.utils.NumberUtils;

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
public class OcQueryStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcDAO ocDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_QUERY;
    }

    @Override
    public String getCommandDescription() {
        return "查询执行中的OC，格式g#" + BotCommands.OC_QUERY + "#OC级别";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 1 || !NumberUtils.isInt(msgArray[0])) {
            return super.sendErrorFormatMsg();
        }

        int rank = Integer.parseInt(msgArray[0]);
        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getRank, rank)
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(), TornOcStatusEnum.PLANNING.getCode())
                .orderByAsc(TornFactionOcDO::getName)
                .orderByAsc(TornFactionOcDO::getStatus)
                .orderByDesc(TornFactionOcDO::getReadyTime)
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            return super.buildTextMsg("未查询到对应OC");
        }

        return super.buildImageMsg(msgManager.buildOcTable(rank + "级执行中OC", ocList));
    }
}