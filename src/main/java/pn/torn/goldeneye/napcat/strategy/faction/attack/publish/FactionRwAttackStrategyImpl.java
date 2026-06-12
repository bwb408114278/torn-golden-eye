package pn.torn.goldeneye.napcat.strategy.faction.attack.publish;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * RW对冲战斗统计策略实现类
 *
 * @author Bai
 * @version 1.1.4
 * @since 2025.12.25
 */
@Component
@RequiredArgsConstructor
public class FactionRwAttackStrategyImpl extends BaseRwStrategy {
    @Override
    public String getCommand() {
        return BotCommands.RW_GOD;
    }

    @Override
    public String getCommandDescription() {
        return "你就是下一个对冲之王";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornFactionRwDO rw = getCurrentRw(sender, msg);
        List<PlayerAttackStatDO> attackList = super.queryAttackList(rw);
        if (CollectionUtils.isEmpty(attackList)) {
            return super.buildTextMsg("未查询到战斗记录");
        }

        return super.buildImageMsg(buildAttackMsg(rw.getFactionName(), rw.getOpponentFactionName(), attackList));
    }

    /**
     * 构建战斗统计表格
     */
    private String buildAttackMsg(String factionName, String opponentFactionName, List<PlayerAttackStatDO> attackList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(factionName + " VS " + opponentFactionName + " 对冲战斗数据统计",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 19);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID", "昵称", "攻击次数", "Hosp", "Leave", "Assist", "Lost",
                "战斗耗时(秒)", "平均耗时(秒)", "攻击在线数", "对手平均ELO",
                "总回合数", "输出评分", "输出伤害", "承受伤害", "打针数", "特殊子弹回合", "烟/闪/泪/椒"));
        tableConfig.setSubTitle(1, 19);

        for (int i = 0; i < attackList.size(); i++) {
            PlayerAttackStatDO attack = attackList.get(i);
            tableData.add(List.of(
                    String.valueOf(i + 1),
                    attack.getUserId().toString(),
                    attack.getNickname(),
                    attack.getTotalAttacks().toString(),
                    attack.getHospCount().toString(),
                    attack.getLeaveCount().toString(),
                    attack.getAssistCount().toString(),
                    attack.getLostCount().toString(),
                    attack.getTotalCombatDuration().toString(),
                    attack.getAvgCombatDuration().toString(),
                    attack.getOnlineOpponentCount().toString(),
                    attack.getAvgOpponentElo().toString(),
                    attack.getTotalRounds().toString(),
                    NumberUtils.addDelimiters(attack.getDamageScore()),
                    NumberUtils.addDelimiters(attack.getDamageDealt()),
                    NumberUtils.addDelimiters(attack.getDamageTaken()),
                    attack.getSyringeUsed().toString(),
                    attack.getSpecialAmmoRounds().toString(),
                    attack.getDebuffTempCount().toString()));
        }

        tableData.add(List.of("采样范围为3分钟内双方行动超过100次的战斗",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        tableConfig.addMerge(attackList.size() + 2, 0, 1, 19);
        tableConfig.setCellStyle(attackList.size() + 2, 0, new TableImageUtils.CellStyle()
                .setAlignment(TableImageUtils.TextAlignment.RIGHT)
                .setFont(new Font("微软雅黑", Font.BOLD, 14)));
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}