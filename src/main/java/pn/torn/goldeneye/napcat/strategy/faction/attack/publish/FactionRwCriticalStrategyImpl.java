package pn.torn.goldeneye.napcat.strategy.faction.attack.publish;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerDefendStatDO;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RW被爆头策略实现类
 *
 * @author Bai
 * @version 1.1.4
 * @since 2026.05.21
 */
@Component
@RequiredArgsConstructor
public class FactionRwCriticalStrategyImpl extends BaseRwStrategy {
    private final TornAttackLogDAO attackLogDao;

    @Override
    public String getCommand() {
        return BotCommands.RW_BIG_HEAD;
    }

    @Override
    public String getCommandDescription() {
        return "到底是谁的头跟磁铁一样吸子弹啊";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornFactionRwDO rw = getCurrentRw(sender, msg);
        LocalDateTime startTime = rw.getStartTime();
        LocalDateTime endTime = rw.getEndTime() == null ? LocalDateTime.now() : rw.getEndTime();
        List<PlayerDefendStatDO> defendList = attackLogDao.queryPlayerHeadHit(rw.getFactionId(), startTime, endTime);
        if (CollectionUtils.isEmpty(defendList)) {
            return super.buildTextMsg("未查询到战斗记录");
        }

        return super.buildImageMsg(buildDefendMsg(rw.getFactionName(), rw.getOpponentFactionName(), defendList));
    }

    /**
     * 构建战斗统计表格
     */
    private String buildDefendMsg(String factionName, String opponentFactionName, List<PlayerDefendStatDO> defendList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(factionName + " VS " + opponentFactionName + " 被爆头榜", "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 7);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID", "昵称", "防御回合数", "被爆头次数", "被爆头几率", "被爆头伤害"));
        tableConfig.setSubTitle(1, 7);

        for (int i = 0; i < defendList.size(); i++) {
            PlayerDefendStatDO defend = defendList.get(i);
            tableData.add(List.of(
                    String.valueOf(i + 1),
                    defend.getUserId().toString(),
                    defend.getNickname(),
                    defend.getHitNum().toString(),
                    defend.getHeadHitNum().toString(),
                    defend.getHeadHitRate().toString() + "%",
                    defend.getHeadHitDamage().toString()));
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}