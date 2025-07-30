package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.ManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSkipDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSkipDO;
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
public class CancelOcSkipStrategyImpl extends ManageMsgStrategy {
    private final TornFactionOcSkipDAO skipDao;

    @Override
    public String getCommand() {
        return "取消OC跳过";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 2 || !NumberUtils.isLong(msgArray[0]) || !NumberUtils.isInt(msgArray[1])) {
            return super.sendErrorFormatMsg();
        }

        long userId = Long.parseLong(msgArray[0]);
        int rank = Integer.parseInt(msgArray[1]);
        TornFactionOcSkipDO old = skipDao.lambdaQuery()
                .eq(TornFactionOcSkipDO::getUserId, userId)
                .eq(TornFactionOcSkipDO::getRank, rank)
                .one();
        if (old == null) {
            return super.buildTextMsg("未找到该设置");
        }

        skipDao.removeById(old.getId());
        return super.buildTextMsg(userId + "已设置正常参加" + rank + "级轮转检测");
    }
}