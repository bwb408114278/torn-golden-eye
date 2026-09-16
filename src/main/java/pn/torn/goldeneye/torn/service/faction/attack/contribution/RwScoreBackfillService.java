package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwRankDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwDTO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwFactionVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwRespVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RW真赛比分与名次一次性回填服务
 *
 * <p>历史真赛的最终比分与名次在功能上线前没有落库。本服务按帮派逐个调用
 * {@code /faction/rankedwars}，对库内已登记场次只补齐仍为null的列（可重复执行、
 * 重跑无副作用），再对满足贡献榜口径且缺少结算行的场次回放名次结算。
 * 该能力同时可作为结算漏场的补漏入口复用。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RwScoreBackfillService {
    /**
     * API单页场次数量
     */
    private static final int PAGE_SIZE = 100;
    /**
     * 翻页排序方向，配合“本页最旧场次已覆盖库内最早场次”的终止判断
     */
    private static final String SORT_DESC = "DESC";
    /**
     * 有效对手得分下限（不含），与贡献榜入选口径一致
     */
    private static final int MIN_OPPONENT_SCORE = 3000;

    private final TornApi tornApi;
    private final TornFactionRwDAO rwDao;
    private final TornFactionRwRankDAO rankDao;
    private final RwRankSettleService rankSettleService;
    private final TornSettingFactionManager factionManager;

    /**
     * 回填全部帮派的RW真赛比分并回放名次结算。
     *
     * @return 逐帮派汇总文本，含匹配场次数、更新场次数、结算回放场次数与仍缺比分的RW ID
     */
    public String backfillAll() {
        StringBuilder summary = new StringBuilder("RW比分回填结果：");
        for (TornSettingFactionDO faction : factionManager.getIdMap().values()) {
            summary.append('\n').append(backfillFaction(faction));
        }

        return summary.toString();
    }

    /**
     * 回填单个帮派的真赛比分并回放名次结算。
     *
     * @param faction 帮派设置
     * @return 该帮派的汇总文本
     */
    private String backfillFaction(TornSettingFactionDO faction) {
        Map<Long, TornFactionRwDO> warMap = rwDao.lambdaQuery()
                .eq(TornFactionRwDO::getFactionId, faction.getId())
                .list()
                .stream()
                .collect(Collectors.toMap(TornFactionRwDO::getId, Function.identity()));
        String prefix = faction.getFactionShortName() + "：";
        if (warMap.isEmpty()) {
            return prefix + "库内无已登记真赛";
        }

        ScoreFillResult fillResult = backfillScores(faction.getId(), warMap);
        int settled = replaySettle(warMap);
        List<Long> missing = warMap.values().stream()
                .filter(rw -> rw.getEndTime() != null)
                .filter(rw -> rw.getOpponentScore() == null)
                .map(TornFactionRwDO::getId)
                .sorted()
                .toList();
        return prefix + "匹配" + fillResult.matched() + "场，更新" + fillResult.updated()
                + "场，结算回放" + settled + "场，缺漏" + missing;
    }

    /**
     * 按页拉取API真赛并补齐库内场次仍为null的比分列。
     *
     * @param factionId 帮派ID
     * @param warMap    库内场次，键为RW ID，方法内会同步更新为回填后的状态
     * @return 匹配场次数与更新场次数
     */
    private ScoreFillResult backfillScores(long factionId, Map<Long, TornFactionRwDO> warMap) {
        LocalDateTime earliestStart = warMap.values().stream()
                .map(TornFactionRwDO::getStartTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        int matched = 0;
        int updated = 0;
        int offset = 0;
        boolean finished = false;
        while (!finished) {
            List<TornFactionRwVO> page = fetchPage(factionId, offset);
            if (CollectionUtils.isEmpty(page)) {
                finished = true;
            } else {
                ScoreFillResult pageResult = fillPage(page, warMap, factionId);
                matched += pageResult.matched();
                updated += pageResult.updated();
                finished = isCovered(page.getLast(), earliestStart);
                offset += PAGE_SIZE;
            }
        }

        return new ScoreFillResult(matched, updated);
    }

    /**
     * 拉取单页真赛场次。
     *
     * @param factionId 帮派ID
     * @param offset    页偏移量
     * @return 本页场次列表；响应为空时返回null
     */
    private List<TornFactionRwVO> fetchPage(long factionId, int offset) {
        TornFactionRwRespVO resp = tornApi.sendRequest(factionId,
                new TornFactionRwDTO(SORT_DESC, PAGE_SIZE, offset), TornFactionRwRespVO.class);
        return resp == null ? null : resp.getRwList();
    }

    /**
     * 补齐单页中已在库场次仍为null的比分列。
     *
     * @param page      本页API场次
     * @param warMap    库内场次，键为RW ID，方法内会同步更新为回填后的状态
     * @param factionId 帮派ID
     * @return 本页匹配场次数与实际更新场次数
     */
    private ScoreFillResult fillPage(List<TornFactionRwVO> page, Map<Long, TornFactionRwDO> warMap, long factionId) {
        int matched = 0;
        int updated = 0;
        for (TornFactionRwVO war : page) {
            TornFactionRwDO exists = warMap.get(war.getId());
            if (exists != null) {
                matched++;
                if (fillNullColumns(exists, war, factionId)) {
                    updated++;
                }
            }
        }

        return new ScoreFillResult(matched, updated);
    }

    /**
     * 判断本页最旧场次是否已经覆盖到库内最早场次，作为翻页终止条件。
     *
     * @param oldestWar     本页最后一个场次（倒序页的最旧场次）
     * @param earliestStart 库内最早场次的开始时间
     * @return true表示无需继续翻页
     */
    private boolean isCovered(TornFactionRwVO oldestWar, LocalDateTime earliestStart) {
        if (earliestStart == null) {
            return true;
        }

        LocalDateTime oldestStart = DateTimeUtils.convertToDateTime(oldestWar.getStart());
        return !oldestStart.isAfter(earliestStart);
    }

    /**
     * 只补齐仍为null的比分列，已有值一律不覆盖。
     *
     * @param exists    库内场次
     * @param war       API场次
     * @param factionId 帮派ID
     * @return true表示确实执行了更新
     */
    private boolean fillNullColumns(TornFactionRwDO exists, TornFactionRwVO war, long factionId) {
        TornFactionRwFactionVO self = war.getSelfFaction(factionId);
        TornFactionRwFactionVO opponent = war.getOpponentFaction(factionId);
        boolean fillTarget = exists.getTargetScore() == null && war.getTarget() > 0;
        boolean fillWinner = exists.getWinnerFactionId() == null && war.getWinner() != 0L;
        boolean fillSelfScore = exists.getFactionScore() == null;
        boolean fillOpponentScore = exists.getOpponentScore() == null;
        String shortName = exists.getOpponentShortName() == null
                ? TornFactionRwVO.defaultShortName(opponent.getName()) : null;
        boolean fillShortName = shortName != null;
        if (!fillTarget && !fillWinner && !fillSelfScore && !fillOpponentScore && !fillShortName) {
            return false;
        }

        rwDao.lambdaUpdate()
                .set(fillTarget, TornFactionRwDO::getTargetScore, war.getTarget())
                .set(fillWinner, TornFactionRwDO::getWinnerFactionId, war.getWinner())
                .set(fillSelfScore, TornFactionRwDO::getFactionScore, self.getScore())
                .set(fillOpponentScore, TornFactionRwDO::getOpponentScore, opponent.getScore())
                .set(fillShortName, TornFactionRwDO::getOpponentShortName, shortName)
                .eq(TornFactionRwDO::getId, exists.getId())
                .update();

        if (fillTarget) {
            exists.setTargetScore(war.getTarget());
        }
        if (fillWinner) {
            exists.setWinnerFactionId(war.getWinner());
        }
        if (fillSelfScore) {
            exists.setFactionScore(self.getScore());
        }
        if (fillOpponentScore) {
            exists.setOpponentScore(opponent.getScore());
        }
        if (fillShortName) {
            exists.setOpponentShortName(shortName);
        }

        return true;
    }

    /**
     * 对符合贡献榜口径但缺少结算行的场次回放名次结算。
     *
     * @param warMap 库内场次，键为RW ID
     * @return 实际回放结算的场次数
     */
    private int replaySettle(Map<Long, TornFactionRwDO> warMap) {
        List<TornFactionRwDO> candidates = warMap.values().stream()
                .filter(rw -> rw.getEndTime() != null)
                .filter(rw -> rw.getOpponentScore() != null && rw.getOpponentScore() > MIN_OPPONENT_SCORE)
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }

        Set<Long> settledRwIds = rankDao.lambdaQuery()
                .in(TornFactionRwRankDO::getRwId, candidates.stream().map(TornFactionRwDO::getId).toList())
                .list()
                .stream()
                .map(TornFactionRwRankDO::getRwId)
                .collect(Collectors.toSet());
        int settled = 0;
        for (TornFactionRwDO rw : candidates) {
            if (settledRwIds.contains(rw.getId())) {
                continue;
            }
            try {
                rankSettleService.settle(rw);
                settled++;
            } catch (RuntimeException e) {
                log.error("RW贡献榜名次回放结算失败，rwId={}", rw.getId(), e);
            }
        }

        return settled;
    }

    /**
     * 比分回填匹配与更新计数，单页与单帮派汇总共用。
     *
     * @param matched 与库内场次匹配上的API场次数
     * @param updated 实际执行更新的场次数
     */
    private record ScoreFillResult(
            int matched,
            int updated) {
    }
}
