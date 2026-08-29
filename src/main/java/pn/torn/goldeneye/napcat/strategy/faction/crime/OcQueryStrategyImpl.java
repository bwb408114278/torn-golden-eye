package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 1.5.1
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcQueryStrategyImpl extends SmthMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcDAO ocDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_QUERY;
    }

    @Override
    public String getCommandDescription() {
        return "查询执行中的OC，格式g#" + BotCommands.OC_QUERY + "#级别1,级别2";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        Set<Integer> rankSet = parseRankSet(msg);
        if (rankSet.isEmpty()) {
            return super.buildTextMsg("需要输入正确的OC级别, 例如g#" + BotCommands.OC_QUERY + "#7,8");
        }

        long factionId = super.getTornFactionIdBySender(sender);
        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getRank, rankSet)
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(), TornOcStatusEnum.PLANNING.getCode())
                .orderByAsc(TornFactionOcDO::getRank)
                .orderByAsc(TornFactionOcDO::getName)
                .orderByAsc(TornFactionOcDO::getStatus)
                .orderByDesc(TornFactionOcDO::getReadyTime)
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            return super.buildTextMsg("未查询到对应OC");
        }

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        String ranks = rankSet.stream().map(String::valueOf).collect(Collectors.joining("、"));
        return super.buildImageMsg(msgManager.buildOcTable(faction.getFactionShortName() + "  "
                + ranks + "级执行中OC", ocList));
    }

    /**
     * 解析OC级别参数，中英文逗号均为分隔符，级别去重升序。
     *
     * @param msg 级别参数串
     * @return 有效级别集合；任一段不是整数时返回空集合
     */
    private Set<Integer> parseRankSet(String msg) {
        String[] rankArray = msg.replace("，", ",").split(",");
        Set<Integer> rankSet = new TreeSet<>();
        for (String rankText : rankArray) {
            String rank = rankText.trim();
            if (!NumberUtils.isInt(rank)) {
                return Set.of();
            }
            rankSet.add(Integer.parseInt(rank));
        }
        return rankSet;
    }
}