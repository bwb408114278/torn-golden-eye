package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIdleRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OC空转榜策略实现类
 *
 * @author Bai
 * @version 1.2.2
 * @since 2026.06.12
 */
@Component
@RequiredArgsConstructor
public class OcIdleRankStrategyImpl extends SmthMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;

    private static final int RANK_LIMIT = 30;

    @Override
    public String getCommand() {
        return BotCommands.OC_IDLE_RANK;
    }

    @Override
    public String getCommandDescription() {
        return "查询最近30天OC空转榜，格式g#" + BotCommands.OC_IDLE_RANK + "(#帮派ID)";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = StringUtils.hasText(msg) ? super.getTornFactionId(msg) : super.getTornFactionIdBySender(sender);

        LocalDate baseMonth = LocalDate.now();
        LocalDateTime fromDate = baseMonth.withDayOfMonth(1).atTime(0, 0, 0);
        LocalDateTime toDate = baseMonth.withDayOfMonth(baseMonth.lengthOfMonth()).atTime(23, 59, 59);
        List<TornFactionOcIdleRankDO> rankList = slotDao.queryIdleRanking(fromDate, toDate, factionId, RANK_LIMIT);
        if (CollectionUtils.isEmpty(rankList)) {
            return super.buildTextMsg("暂未查询到" + baseMonth.getMonthValue() + "月完成的OC");
        }

        String title = buildTitle(factionId, baseMonth);
        return super.buildImageMsg(buildRankTable(rankList, title));
    }

    /**
     * 构建标题
     */
    private String buildTitle(long factionId, LocalDate baseMonth) {
        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        return faction.getFactionShortName() + "  " + baseMonth.getMonthValue() + "月OC空转榜";
    }

    /**
     * 构建OC空转排行榜表格
     */
    private String buildRankTable(List<TornFactionOcIdleRankDO> rankingList, String title) {
        Set<Long> userIdSet = rankingList.stream().map(TornFactionOcIdleRankDO::getUserId).collect(Collectors.toSet());
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdSet);

        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(title, "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 5);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "空转时长", "OC次数"));
        tableConfig.setSubTitle(1, 5);

        for (int i = 0; i < rankingList.size(); i++) {
            TornFactionOcIdleRankDO ranking = rankingList.get(i);
            TornUserDO user = userMap.get(ranking.getUserId());
            tableData.add(List.of(
                    String.valueOf(i + 1),
                    ranking.getUserId().toString(),
                    user == null ? "未知" : user.getNickname(),
                    formatDuration(ranking.getIdleSeconds()),
                    ranking.getOcCount().toString()));
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long seconds) {
        BigDecimal days = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(86400), 2, RoundingMode.HALF_UP);
        return days + "天";
    }
}
