package pn.torn.goldeneye.napcat.strategy.faction.attack.publish;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.dao.faction.revive.TornFactionRwReviveDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.revive.TornFactionRwReviveDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
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
        return "神医的恩情还不完！";
    }

    @Override
    public List<ImageQqMsg> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornFactionRwDO rw = getCurrentRw(sender, msg);
        if (rw == null) {
            throw new BizException("暂无RW真赛数据");
        }

        // 使用滚动窗口SQL查询活跃对战时间窗口（3分钟内双方攻击>=100次）
        List<AttackTimeWindowDO> windowList = queryActiveTimeWindows(rw);
        if (windowList == null || windowList.isEmpty()) {
            throw new BizException("暂无对冲数据");
        }

        // 取最早和最晚的时间边界，下推到SQL做过滤
        TornSettingFactionDO faction = factionManager.getIdMap().get(rw.getFactionId());
        List<TornFactionRwReviveDO> reviveList = reviveDao.lambdaQuery()
                .eq(TornFactionRwReviveDO::getFactionId, faction.getId())
                .eq(TornFactionRwReviveDO::getRwId, rw.getId())
                .list();
        if (reviveList == null || reviveList.isEmpty()) {
            throw new BizException("暂无神医榜数据");
        }

        // 精确过滤：只保留落在活跃窗口内的复活记录
        List<TornFactionRwReviveDO> filteredList = reviveList.stream()
                .filter(revive -> windowList.stream()
                        .anyMatch(window -> window.contains(revive.getReviveTime())))
                .collect(Collectors.toMap(this::buildReviveUniqueKey, Function.identity(),
                        (first, second) -> second, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        if (filteredList.isEmpty()) {
            throw new BizException("暂无神医榜数据");
        }

        List<RankRow> rankRows = buildReviveSummary(filteredList, windowList);
        return buildImageMsg(buildRwReviveRankImage(rw.getFactionName(), rw.getOpponentFactionName(), rankRows));
    }

    /**
     * 构建表格图片
     */
    private String buildRwReviveRankImage(String factionName, String opponentFactionName, List<RankRow> rankRows) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(factionName + " VS " + opponentFactionName + " 对冲复活数据统计",
                "", "", "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 9);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID", "昵称", "帮派", "复活次数", "成功次数", "成功率", "回血量", "复活频率"));
        tableConfig.setSubTitle(1, 9);

        for (int i = 0; i < rankRows.size(); i++) {
            RankRow row = rankRows.get(i);
            tableData.add(List.of(
                    String.valueOf(i + 1),
                    String.valueOf(row.userId()),
                    row.name(),
                    row.factionName(),
                    String.valueOf(row.reviveCount()),
                    String.valueOf(row.successCount()),
                    String.format(Locale.ROOT, "%.2f%%", row.successRate().doubleValue()),
                    String.valueOf(row.healAmount()),
                    String.format(Locale.ROOT, "%.2f/分钟", row.reviveFrequency().doubleValue())
            ));
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 构建复活统计数据
     *
     * @param reviveList 已过滤的复活记录
     * @param windowList 活跃对战时间窗口
     */
    private List<RankRow> buildReviveSummary(List<TornFactionRwReviveDO> reviveList,
                                              List<AttackTimeWindowDO> windowList) {
        Map<Long, List<TornFactionRwReviveDO>> groupMap = reviveList.stream()
                .filter(item -> item.getReviverId() != null)
                .collect(Collectors.groupingBy(TornFactionRwReviveDO::getReviverId));

        List<RankRow> rankRows = new ArrayList<>();
        List<Map.Entry<Long, List<TornFactionRwReviveDO>>> userReviveMap = groupMap.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Long, List<TornFactionRwReviveDO>> entry) ->
                        entry.getValue().size()).reversed())
                .toList();

        for (Map.Entry<Long, List<TornFactionRwReviveDO>> entry : userReviveMap) {
            List<TornFactionRwReviveDO> userRevives = entry.getValue();
            TornFactionRwReviveDO first = userRevives.getFirst();
            long reviveCount = userRevives.size();
            long successCount = userRevives.stream().filter(TornFactionRwReviveDO::getSuccess).count();
            BigDecimal successRate = reviveCount == 0 ?
                    BigDecimal.ZERO : BigDecimal.valueOf(successCount * 100D / reviveCount);
            int healAmount = userRevives.stream()
                    .mapToInt(item -> item.getHealAmount() == null ? 0 : item.getHealAmount())
                    .sum();
            BigDecimal reviveFrequency = calcReviveFrequency(userRevives, reviveCount, windowList);

            rankRows.add(new RankRow(first.getReviverId(), first.getReviverName(), first.getReviverFactionName(),
                    reviveCount, successCount, successRate, healAmount, reviveFrequency));
        }

        return rankRows;
    }

    /**
     * 按时间窗口维度计算复活频率：总次数 / 各窗口内时间跨度之和（分钟）
     */
    private BigDecimal calcReviveFrequency(List<TornFactionRwReviveDO> userRevives,
                                            long reviveCount,
                                            List<AttackTimeWindowDO> windowList) {
        long totalSeconds = windowList.stream()
                .mapToLong(window -> calcWindowReviveSeconds(userRevives, window))
                .sum();
        double minutes = totalSeconds / 60D;
        return minutes <= 0D ? BigDecimal.ZERO
                : BigDecimal.valueOf(reviveCount).divide(BigDecimal.valueOf(minutes), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 计算单个时间窗口内玩家复活的时间跨度（秒）
     */
    private long calcWindowReviveSeconds(List<TornFactionRwReviveDO> userRevives,
                                          AttackTimeWindowDO window) {
        LocalDateTime first = null;
        LocalDateTime last = null;
        for (TornFactionRwReviveDO revive : userRevives) {
            LocalDateTime rt = revive.getReviveTime();
            if (rt == null || !window.contains(rt)) continue;
            if (first == null || rt.isBefore(first)) first = rt;
            if (last == null || rt.isAfter(last)) last = rt;
        }
        if (first == null || first.equals(last)) return 0L;
        return Duration.between(first, last).toSeconds();
    }

    /**
     * 构建唯一Key
     */
    private String buildReviveUniqueKey(TornFactionRwReviveDO revive) {
        return revive.getReviverId() + "#" + revive.getTargetId() + "#" + revive.getReviveTime();
    }

    private record RankRow(long userId,
                           String name,
                           String factionName,
                           long reviveCount,
                           long successCount,
                           BigDecimal successRate,
                           int healAmount,
                           BigDecimal reviveFrequency) {
    }
}
