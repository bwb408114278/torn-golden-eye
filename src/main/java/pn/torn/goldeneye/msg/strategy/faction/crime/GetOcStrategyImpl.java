package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.ManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
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
public class GetOcStrategyImpl extends ManageMsgStrategy {
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;

    @Override
    public String getCommand() {
        return "OC测试";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        if (!NumberUtils.isInt(msg)) {
            return super.sendErrorFormatMsg();
        }

        TornFactionOcDO oc = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.PLANNING.getCode())
                .eq(TornFactionOcDO::getRank, Integer.parseInt(msg))
                .one();
        if (oc == null) {
            return super.buildTextMsg("未查询到该级别状态为Planning的OC");
        }

        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().eq(TornFactionOcSlotDO::getOcId, oc.getId()).list();
        return super.buildTextMsg("当前级别Planning的OC详情如下:" +
                "\n执行时间: " + DateTimeUtils.convertToString(oc.getReadyTime()) +
                "\n岗位列表: " + buildSlotMsg(slotList));
    }

    /**
     * 构建岗位详细消息
     *
     * @return 岗位消息消息
     */
    private String buildSlotMsg(List<TornFactionOcSlotDO> slots) {
        StringBuilder builder = new StringBuilder();
        for (TornFactionOcSlotDO slot : slots) {
            builder.append("岗位: ").append(slot.getPosition())
                    .append(", 人员: ").append(slot.getUserId())
                    .append(", 成功率: ").append(slot.getPassRate())
                    .append(", 加入时间: ").append(DateTimeUtils.convertToString(slot.getJoinTime()))
                    .append("\n");
        }
        return builder.toString();
    }
}