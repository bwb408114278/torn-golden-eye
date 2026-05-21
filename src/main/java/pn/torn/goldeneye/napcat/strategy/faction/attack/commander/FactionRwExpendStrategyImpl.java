package pn.torn.goldeneye.napcat.strategy.faction.attack.commander;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackItemDO;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对冲战斗统计策略实现类
 *
 * @author Bai
 * @version 1.1.4
 * @since 2025.12.29
 */
@Component
@RequiredArgsConstructor
public class FactionRwExpendStrategyImpl extends BaseRwStrategy {
    private final TornAttackLogDAO attackLogDao;

    @Override
    public String getCommand() {
        return BotCommands.RW_EXPEND;
    }

    @Override
    public String getCommandDescription() {
        return "看看这群人到底多能吃";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.QUARTERMASTER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornFactionRwDO rw = getCurrentRw(sender, msg);
        LocalDateTime startTime = rw.getStartTime();
        LocalDateTime endTime = rw.getEndTime() == null ? LocalDateTime.now() : rw.getEndTime();
        List<PlayerAttackItemDO> attackList = attackLogDao.queryPlayerAttackItem(rw.getFactionId(), startTime, endTime);
        if (CollectionUtils.isEmpty(attackList)) {
            return super.buildTextMsg("未查询到物品使用记录");
        }

        return super.buildImageMsg(buildItemMsg(rw.getFactionName(), rw.getOpponentFactionName(), attackList));
    }

    /**
     * 构建战斗统计表格
     */
    private String buildItemMsg(String factionName, String opponentFactionName, List<PlayerAttackItemDO> itemList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(factionName + " VS " + opponentFactionName + " 物品消耗统计",
                ""));
        tableConfig.addMerge(0, 0, 1, 2);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("物品名称", "数量"));
        tableConfig.setSubTitle(1, 2);

        for (PlayerAttackItemDO item : itemList) {
            tableData.add(List.of(
                    item.getAttackerItemName(),
                    item.getNum().toString()));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}