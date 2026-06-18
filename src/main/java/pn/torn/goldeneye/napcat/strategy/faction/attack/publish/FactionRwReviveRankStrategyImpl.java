package pn.torn.goldeneye.napcat.strategy.faction.attack.publish;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.dao.faction.revive.TornFactionRwReviveDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.revive.TornFactionRwReviveDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.Color;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RW神医榜
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
@Component
@RequiredArgsConstructor
public class FactionRwReviveRankStrategyImpl extends BaseRwStrategy {
    private final TornFactionRwReviveDAO reviveDao;
    private final TornSettingFactionManager factionManager;

    @Override
    public String getCommand() {
        return BotCommands.RW_REVIVE_RANK;
    }

    @Override
    public String getCommandDescription() {
        return "RW神医榜";
    }

    @Override
    public List<ImageQqMsg> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = getTornFactionIdBySender(sender);
        TornSettingFactionDO faction = factionManager.getIdMap().get(factionId);
        if (faction == null) {
            throw new BizException("未找到帮派信息");
        }
        TornFactionRwDO rw = getCurrentRw(sender, msg);
        if (rw == null) {
            throw new BizException("暂无RW数据");
        }
        return buildImageMsg(buildRwReviveRankImage(faction, rw));
    }

    private String buildRwReviveRankImage(TornSettingFactionDO faction, TornFactionRwDO rw) {
        List<TimeWindow> windowList = buildAttackTimeWindowList(rw);
        List<TornFactionRwReviveDO> reviveList = reviveDao.lambdaQuery()
                .eq(TornFactionRwReviveDO::getFactionId, faction.getId())
                .eq(TornFactionRwReviveDO::getRwId, rw.getId())
                .orderByDesc(TornFactionRwReviveDO::getReviveTime)
                .list();
        if (reviveList == null || reviveList.isEmpty()) {
            throw new BizException("暂无神医榜数据");
        }

        List<TornFactionRwReviveDO> filteredList = reviveList.stream()
                .filter(revive -> revive.getReviveTime() != null)
                .filter(revive -> windowList.stream().anyMatch(window -> window.contains(revive.getReviveTime())))
                .collect(Collectors.toMap(this::buildReviveUniqueKey, Function.identity(), (first, second) -> second, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        if (filteredList.isEmpty()) {
            throw new BizException("暂无神医榜数据");
        }

        double totalMinutes = windowList.stream()
                .mapToLong(window -> Duration.between(window.start(), window.end()).toSeconds())
                .sum() / 60D;

        Map<Long, List<TornFactionRwReviveDO>> groupMap = filteredList.stream()
                .filter(item -> item.getReviverId() != null)
                .collect(Collectors.groupingBy(TornFactionRwReviveDO::getReviverId));

        List<RankRow> rankRows = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<Long, List<TornFactionRwReviveDO>> entry : groupMap.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Long, List<TornFactionRwReviveDO>> entry) -> entry.getValue().size()).reversed())
                .toList()) {
            List<TornFactionRwReviveDO> userRevives = entry.getValue();
            TornFactionRwReviveDO first = userRevives.getFirst();
            long successCount = userRevives.stream().filter(revive -> Boolean.TRUE.equals(revive.getSuccess())).count();
            long reviveCount = userRevives.size();
            BigDecimal successRate = reviveCount == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(successCount * 100D / reviveCount);
            int healAmount = userRevives.stream().mapToInt(item -> item.getHealAmount() == null ? 0 : item.getHealAmount()).sum();
            BigDecimal reviveFrequency = totalMinutes <= 0D ? BigDecimal.ZERO : BigDecimal.valueOf(reviveCount).divide(BigDecimal.valueOf(totalMinutes), 2, java.math.RoundingMode.HALF_UP);
            rankRows.add(new RankRow(rank++, first.getReviverId(), first.getReviverName(), first.getReviverFactionName(), reviveCount, successCount, successRate, healAmount, reviveFrequency));
        }

        List<List<String>> tableData = new ArrayList<>();
        tableData.add(List.of("Rank", "帮派", "ID", "昵称", "复活次数", "成功次数", "成功率", "回血量", "复活频率"));
        for (RankRow row : rankRows) {
            tableData.add(List.of(
                    String.valueOf(row.rank()),
                    row.factionName(),
                    String.valueOf(row.userId()),
                    row.name(),
                    String.valueOf(row.reviveCount()),
                    String.valueOf(row.successCount()),
                    String.format(Locale.ROOT, "%.2f%%", row.successRate().doubleValue()),
                    String.valueOf(row.healAmount()),
                    String.format(Locale.ROOT, "%.2f/分钟", row.reviveFrequency().doubleValue())
            ));
        }

        TableImageUtils.TableConfig config = new TableImageUtils.TableConfig()
                .setDefaultCellHeight(44)
                .setDefaultBgColor(new Color(250, 250, 250))
                .setBorderColor(new Color(180, 180, 180));
        return TableImageUtils.renderTableToBase64(new TableDataBO(tableData, config));
    }

    private String buildReviveUniqueKey(TornFactionRwReviveDO revive) {
        return revive.getReviverId() + "#" + revive.getTargetId() + "#" + revive.getReviveTime();
    }

    private record RankRow(int rank, long userId, String name, String factionName, long reviveCount, long successCount, BigDecimal successRate, int healAmount, BigDecimal reviveFrequency) {
    }
}
