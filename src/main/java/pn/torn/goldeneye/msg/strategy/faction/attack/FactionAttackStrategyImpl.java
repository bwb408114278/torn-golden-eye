package pn.torn.goldeneye.msg.strategy.faction.attack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对冲战斗统计策略实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Component
@RequiredArgsConstructor
public class FactionAttackStrategyImpl extends PnMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornAttackLogDAO attackLogDao;

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
        int windowMinutes = 3;
        int minBattleCount = 100;
        LocalDateTime startTime = LocalDateTime.of(2025, 9, 4, 21, 0, 0);
        LocalDateTime endTime = LocalDateTime.now();
        List<PlayerAttackStatDO> attackList = attackLogDao.queryPlayerAttackStat(TornConstants.FACTION_PN_ID,
                windowMinutes, minBattleCount, startTime, endTime);

        return super.buildImageMsg(buildRankingMsg(TornConstants.FACTION_PN_ID, "Xenon", attackList));
    }

    /**
     * 构建战斗统计表格
     */
    private String buildRankingMsg(long factionId, String opponentFactionName, List<PlayerAttackStatDO> attackList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        tableData.add(List.of(faction.getFactionName() + "VS" + opponentFactionName + "对冲战斗数据统计",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 17);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("ID", "昵称 ", "攻击次数", "Hosp", "Leave", "Assist", "Lost",
                "战斗耗时(秒)", "平均耗时(秒)", "对手在线", "对手平均ELO",
                "总回合数", "输出伤害", "承受伤害", "打针数", "特殊子弹次数", "烟/闪/泪/椒"));
        tableConfig.setSubTitle(1, 17);

        for (PlayerAttackStatDO attack : attackList) {
            tableData.add(List.of(
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
                    attack.getDamageDealt().toString(),
                    attack.getDamageTaken().toString(),
                    attack.getSyringeUsed().toString(),
                    attack.getSpecialAmmoRounds().toString(),
                    attack.getDebuffTempCount().toString()));
        }

        tableData.add(List.of("采样范围为3分钟内双方行动超过100次的战斗",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        tableConfig.addMerge(attackList.size() + 2, 0, 1, 17);
        tableConfig.setCellStyle(attackList.size() + 2, 0, new TableImageUtils.CellStyle()
                .setAlignment(TableImageUtils.TextAlignment.RIGHT));
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}