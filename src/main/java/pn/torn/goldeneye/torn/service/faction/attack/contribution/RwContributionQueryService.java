package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwRankDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionReportBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionRowBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionWarBO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RW真赛贡献榜查询编排服务
 *
 * <p>入选口径、得分聚合与排序规则只在 {@link #buildReport(long)} 内实现一次：
 * 取本帮派已结束且对手得分大于3000的最近3场真赛，join名次结算表后按成员汇总。
 * 某场缺少结算行只标记该场，不阻塞其余场次，也不触发懒结算。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Service
@RequiredArgsConstructor
public class RwContributionQueryService {
    /**
     * 有效对手得分下限（不含）
     */
    private static final int MIN_OPPONENT_SCORE = 3000;
    /**
     * 入选场次上限，与贡献榜口径的“最近3场”一致
     */
    private static final int MAX_WAR_COUNT = 3;
    /**
     * 总分保留的小数位
     */
    private static final int SCORE_SCALE = 1;
    /**
     * 榜单排序：总分倒序，并列时上榜场次多者优先，再按用户ID升序
     */
    private static final Comparator<RwContributionRowBO> ROW_COMPARATOR =
            Comparator.<RwContributionRowBO, BigDecimal>comparing(RwContributionRowBO::totalScore).reversed()
                    .thenComparing(Comparator.comparingInt(RwContributionRowBO::warCount).reversed())
                    .thenComparingLong(RwContributionRowBO::userId);

    private final TornFactionRwDAO rwDao;
    private final TornFactionRwRankDAO rankDao;
    private final RwContributionCalculator calculator;

    /**
     * 构建指定帮派的RW真赛贡献榜报告。
     *
     * @param factionId 帮派ID
     * @return 贡献榜报告；无合格场次时场次与榜单行均为空列表
     */
    public RwContributionReportBO buildReport(long factionId) {
        List<TornFactionRwDO> warList = queryQualifiedWars(factionId);
        if (warList.isEmpty()) {
            return new RwContributionReportBO(List.of(), List.of(), LocalDateTime.now());
        }

        List<Long> rwIdList = warList.stream().map(TornFactionRwDO::getId).toList();
        List<TornFactionRwRankDO> rankList = rankDao.lambdaQuery()
                .in(TornFactionRwRankDO::getRwId, rwIdList)
                .list();
        Set<Long> settledRwIds = rankList.stream()
                .map(TornFactionRwRankDO::getRwId)
                .collect(Collectors.toSet());
        Map<Long, RwContributionWarBO> warMap = HashMap.newHashMap(warList.size());
        List<RwContributionWarBO> wars = new ArrayList<>(warList.size());
        for (TornFactionRwDO war : warList) {
            RwContributionWarBO warBo = toWarBo(war, settledRwIds.contains(war.getId()));
            wars.add(warBo);
            warMap.put(warBo.rwId(), warBo);
        }

        return new RwContributionReportBO(wars, buildRows(rankList, warMap), LocalDateTime.now());
    }

    /**
     * 查询入选场次：本帮派已结束、对手得分大于3000，按结束时间倒序取前3场。
     *
     * @param factionId 帮派ID
     * @return 入选场次，按结束时间倒序
     */
    private List<TornFactionRwDO> queryQualifiedWars(long factionId) {
        return rwDao.lambdaQuery()
                .eq(TornFactionRwDO::getFactionId, factionId)
                .isNotNull(TornFactionRwDO::getEndTime)
                .gt(TornFactionRwDO::getOpponentScore, MIN_OPPONENT_SCORE)
                .orderByDesc(TornFactionRwDO::getEndTime)
                .last("LIMIT " + MAX_WAR_COUNT)
                .list();
    }

    /**
     * 将RW持久化对象转换为场次展示模型。
     *
     * @param war     入选场次
     * @param settled 该场是否已有结算行
     * @return 场次展示模型
     */
    private RwContributionWarBO toWarBo(TornFactionRwDO war, boolean settled) {
        return new RwContributionWarBO(war.getId(), war.getOpponentShortName(), war.getOpponentFactionName(),
                war.getOpponentScore(), calculator.coefficient(war.getOpponentScore()), settled);
    }

    /**
     * 按成员聚合各场得分并排序。
     *
     * @param rankList 入选场次的全量结算行
     * @param warMap   场次展示模型，键为RW ID
     * @return 榜单行，按总分倒序
     */
    private List<RwContributionRowBO> buildRows(List<TornFactionRwRankDO> rankList,
                                                Map<Long, RwContributionWarBO> warMap) {
        Map<Long, List<TornFactionRwRankDO>> rankByUser = rankList.stream()
                .collect(Collectors.groupingBy(TornFactionRwRankDO::getUserId));
        List<RwContributionRowBO> rows = new ArrayList<>(rankByUser.size());
        for (Map.Entry<Long, List<TornFactionRwRankDO>> entry : rankByUser.entrySet()) {
            rows.add(buildRow(entry.getKey(), entry.getValue(), warMap));
        }
        rows.sort(ROW_COMPARATOR);
        return rows;
    }

    /**
     * 聚合单个成员在入选场次内的得分。
     *
     * @param userId   成员Torn用户ID
     * @param rankList 该成员在入选场次内的结算行
     * @param warMap   场次展示模型，键为RW ID
     * @return 榜单行
     */
    private RwContributionRowBO buildRow(long userId, List<TornFactionRwRankDO> rankList,
                                         Map<Long, RwContributionWarBO> warMap) {
        BigDecimal totalScore = BigDecimal.ZERO;
        Map<Long, Integer> rankByRwId = HashMap.newHashMap(rankList.size());
        String nickname = null;
        int warCount = 0;
        for (TornFactionRwRankDO rank : rankList) {
            RwContributionWarBO war = warMap.get(rank.getRwId());
            if (war == null) {
                continue;
            }

            nickname = rank.getNickname();
            rankByRwId.put(war.rwId(), rank.getRankNum());
            totalScore = totalScore.add(calculator.warScore(rank.getRankNum(), war.opponentScore()));
            warCount++;
        }

        return new RwContributionRowBO(userId, nickname,
                totalScore.setScale(SCORE_SCALE, RoundingMode.HALF_UP), warCount, rankByRwId);
    }
}
