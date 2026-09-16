package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwRankDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;
import pn.torn.goldeneye.repository.model.torn.PlayerAttackStatDO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RW真赛战神榜名次结算服务
 *
 * <p>结算口径与 {@code g#RW战神} 完全一致：先用活跃时间窗口锁定对冲时段，
 * 再按双方出手聚合输出评分并降序取行号作为名次。同一场战争可重复结算，
 * 重结算前先物理删除旧行，避免逻辑删除行占用唯一索引。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RwRankSettleService {
    private final TornFactionRwRankDAO rankDao;
    private final TornAttackLogDAO attackLogDao;

    /**
     * 结算一场真赛的战神榜名次。
     *
     * <p>先按RW ID物理清空历史结算行，再按当前战斗日志重算并批量插入，
     * 删除与插入处于同一事务，避免出现半场名次。</p>
     *
     * @param rw 已结束的RW对象
     */
    @Transactional
    public void settle(TornFactionRwDO rw) {
        rankDao.physicalDeleteByRwId(rw.getId());
        List<PlayerAttackStatDO> attackList = queryAttackList(rw);
        if (CollectionUtils.isEmpty(attackList)) {
            log.warn("RW名次结算未查询到战斗记录，rwId={}", rw.getId());
            return;
        }

        List<TornFactionRwRankDO> rankList = new ArrayList<>(attackList.size());
        for (int index = 0; index < attackList.size(); index++) {
            rankList.add(buildRank(rw.getId(), index + 1, attackList.get(index)));
        }
        rankDao.saveBatch(rankList);
    }

    /**
     * 按RW起止时间查询战神榜口径的攻击统计。
     *
     * @param rw RW对象
     * @return 按输出评分降序的玩家统计；无活跃窗口时返回空列表
     */
    private List<PlayerAttackStatDO> queryAttackList(TornFactionRwDO rw) {
        LocalDateTime endTime = rw.getEndTime() == null ? LocalDateTime.now() : rw.getEndTime();
        List<AttackTimeWindowDO> windows = attackLogDao.queryActiveTimeWindows(
                rw.getFactionId(), rw.getOpponentFactionId(),
                TornConstants.RW_ACTIVE_WINDOW_MINUTES, TornConstants.RW_ACTIVE_MIN_BATTLE_COUNT,
                rw.getStartTime(), endTime);
        if (CollectionUtils.isEmpty(windows)) {
            return List.of();
        }

        return attackLogDao.queryPlayerAttackStatByWindows(
                rw.getFactionId(), rw.getOpponentFactionId(), windows);
    }

    /**
     * 将玩家统计转换为结算行。
     *
     * @param rwId        RW ID
     * @param rankNum     名次（统计结果行号）
     * @param playerStats 玩家攻击统计
     * @return 结算行
     */
    private TornFactionRwRankDO buildRank(long rwId, int rankNum, PlayerAttackStatDO playerStats) {
        TornFactionRwRankDO rank = new TornFactionRwRankDO();
        rank.setRwId(rwId);
        rank.setUserId(playerStats.getUserId());
        rank.setNickname(playerStats.getNickname());
        rank.setRankNum(rankNum);
        rank.setDamageScore(playerStats.getDamageScore());
        return rank;
    }
}
