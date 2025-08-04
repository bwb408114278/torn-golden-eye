package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.ManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcQueryStrategyImpl extends ManageMsgStrategy {
    private final TornApi tornApi;
    private final TornFactionOcService ocService;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_QUERY;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 2 || !NumberUtils.isInt(msgArray[1])) {
            return super.sendErrorFormatMsg();
        }

        TornOcStatusEnum ocStatus = getOcStatus(msgArray[0]);
        if (ocStatus == null) {
            return super.sendErrorFormatMsg();
        }

        TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(ocStatus), TornFactionOcVO.class);
        ocService.updateOc(oc.getCrimes());

        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getStatus, ocStatus.getCode())
                .eq(TornFactionOcDO::getRank, Integer.parseInt(msgArray[1]))
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            return super.buildTextMsg("未查询到对应OC");
        }

        List<Long> ocIdList = ocList.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().in(TornFactionOcSlotDO::getOcId, ocIdList).list();
        return super.buildTextMsg(buildOcListMsg(ocList, slotList));
    }

    /**
     * 构建OC列表消息
     *
     * @return 消息内容
     */
    private String buildOcListMsg(List<TornFactionOcDO> ocList, List<TornFactionOcSlotDO> slotList) {
        List<Long> userIdList = slotList.stream().map(TornFactionOcSlotDO::getUserId).filter(Objects::nonNull).toList();
        List<TornUserDO> userList = userDao.lambdaQuery().in(TornUserDO::getId, userIdList).list();

        StringBuilder builder = new StringBuilder();
        for (TornFactionOcDO oc : ocList) {
            builder.append("\nID: ").append(oc.getId());
            if (oc.isHasCurrent()) {
                builder.append(" 今日轮转队 ");
            }
            if (TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus())) {
                builder.append(" 执行时间: ").append(DateTimeUtils.convertToString(oc.getReadyTime()));
            }
            builder.append(" 岗位列表: ");

            List<TornFactionOcSlotDO> currenSlotList = slotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .sorted(Comparator.comparing(TornFactionOcSlotDO::getPosition))
                    .toList();
            for (TornFactionOcSlotDO slot : currenSlotList) {
                TornUserDO user = slot.getUserId() == null ?
                        null :
                        userList.stream().filter(u -> u.getId().equals(slot.getUserId())).findAny().orElse(null);
                builder.append("\n").append(slot.getPosition()).append(": ")
                        .append(user == null ?
                                "空缺" :
                                user.getNickname() + "[" + user.getId() + "] 成功率: " + slot.getPassRate());
            }
        }

        return builder.toString().replaceFirst("\n", "");
    }

    /**
     * 获取OC状态
     *
     * @param command 命令
     */
    private TornOcStatusEnum getOcStatus(String command) {
        return switch (command.toUpperCase()) {
            case "REC" -> TornOcStatusEnum.RECRUITING;
            case "PLAN" -> TornOcStatusEnum.PLANNING;
            default -> null;
        };
    }
}