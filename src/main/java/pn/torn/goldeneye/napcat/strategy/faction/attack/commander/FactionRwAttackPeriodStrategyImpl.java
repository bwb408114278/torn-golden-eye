package pn.torn.goldeneye.napcat.strategy.faction.attack.commander;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionAttackDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RW对冲战斗统计策略实现类
 *
 * @author Bai
 * @version 1.1.4
 * @since 2025.12.25
 */
@Component
@RequiredArgsConstructor
public class FactionRwAttackPeriodStrategyImpl extends BaseRwStrategy {
    private final TornFactionAttackDAO attackDao;

    @Override
    public String getCommand() {
        return BotCommands.RW_ATTACK_PERIOD;
    }

    @Override
    public String getCommandDescription() {
        return "RW真赛开场攻击频率分析";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.WAR_COMMANDER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornFactionRwDO rw = getCurrentRw(sender, msg);
        LocalDateTime startTime = rw.getStartTime();
        LocalDateTime endTime = rw.getStartTime().plusMinutes(2L);
        List<TornFactionAttackDO> attackList = attackDao.lambdaQuery()
                .ge(TornFactionAttackDO::getAttackStartTime, startTime)
                .le(TornFactionAttackDO::getAttackStartTime, endTime)
                .list();
        if (CollectionUtils.isEmpty(attackList)) {
            return super.buildTextMsg("未查询到战斗记录");
        }

        return super.buildImageMsg(buildAttackMsg(rw.getStartTime(), rw.getFactionId(),
                rw.getFactionName(), rw.getOpponentFactionName(), attackList));
    }

    /**
     * 构建战斗统计表格
     */
    private String buildAttackMsg(LocalDateTime startTime, long factionId, String factionName,
                                  String opponentFactionName, List<TornFactionAttackDO> attackList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        int allSelfCount = countUserNum(factionId, attackList, true);
        int allOpponentCount = countUserNum(factionId, attackList, false);

        tableData.add(List.of("时间窗口", factionName, opponentFactionName));

        LocalDateTime end = startTime.plusSeconds(10L);
        int selfCount = countAttackNum(factionId, end, attackList, true);
        int opponentCount = countAttackNum(factionId, end, attackList, false);
        tableData.add(List.of("10s", String.valueOf(selfCount), String.valueOf(opponentCount)));

        end = end.plusSeconds(10L);
        selfCount = countAttackNum(factionId, end, attackList, true);
        opponentCount = countAttackNum(factionId, end, attackList, false);
        tableData.add(List.of("20s", String.valueOf(selfCount), String.valueOf(opponentCount)));

        end = end.plusSeconds(10L);
        selfCount = countAttackNum(factionId, end, attackList, true);
        opponentCount = countAttackNum(factionId, end, attackList, false);
        tableData.add(List.of("30s", String.valueOf(selfCount), String.valueOf(opponentCount)));

        end = end.plusSeconds(30L);
        selfCount = countAttackNum(factionId, end, attackList, true);
        opponentCount = countAttackNum(factionId, end, attackList, false);
        tableData.add(List.of("60s", String.valueOf(selfCount), String.valueOf(opponentCount)));

        end = end.plusSeconds(61L);
        selfCount = countAttackNum(factionId, end, attackList, true);
        opponentCount = countAttackNum(factionId, end, attackList, false);
        tableData.add(List.of("120s", String.valueOf(selfCount), String.valueOf(opponentCount)));

        tableData.add(List.of("人数", String.valueOf(allSelfCount), String.valueOf(allOpponentCount)));

        int selfAttackCount = countAttackNum(factionId, null, attackList, true);
        int opponentAttackCount = countAttackNum(factionId, null, attackList, false);
        tableData.add(List.of("人均出手/分钟",
                BigDecimal.valueOf(selfAttackCount).divide(BigDecimal.valueOf(allSelfCount), 3, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(0.5))
                        .toString(),
                BigDecimal.valueOf(opponentAttackCount).divide(BigDecimal.valueOf(allOpponentCount), 3, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(0.5))
                        .toString()));

        TableImageUtils.CellStyle style = new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14));
        tableConfig.setCellStyle(6, 0, style);
        tableConfig.setCellStyle(6, 1, style);
        tableConfig.setCellStyle(6, 2, style);
        tableConfig.setCellStyle(7, 0, style);
        tableConfig.setCellStyle(7, 1, style);
        tableConfig.setCellStyle(7, 2, style);

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 计算攻击次数
     */
    private int countAttackNum(long factionId, LocalDateTime endTime,
                               List<TornFactionAttackDO> attackList, boolean isSelf) {
        return attackList.stream()
                .filter(a -> endTime == null || a.getAttackStartTime().isBefore(endTime))
                .filter(a -> a.getAttackFactionId().equals(factionId) == isSelf)
                .toList()
                .size();
    }

    /**
     * 计算人数
     */
    private int countUserNum(long factionId, List<TornFactionAttackDO> attackList, boolean isSelf) {
        return attackList.stream()
                .filter(a -> a.getAttackFactionId().equals(factionId) == isSelf)
                .map(TornFactionAttackDO::getAttackUserId)
                .collect(Collectors.toSet())
                .size();
    }
}