package pn.torn.goldeneye.torn.service.faction.attack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwStatWindowDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.faction.attack.AttackTimeWindowDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwStatWindowDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwAttackFrequencySummaryVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO;
import pn.torn.goldeneye.utils.RwStatWindowCodeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RW对冲统计窗口生命周期服务。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@Service
@RequiredArgsConstructor
public class RwStatWindowService {
    private static final int WINDOW_MINUTES = 3;
    private static final int MIN_BATTLE_COUNT = 100;

    private final TornFactionRwStatWindowDAO windowDao;
    private final TornAttackLogDAO attackLogDao;

    /**
     * 按需刷新RW窗口定义。
     *
     * @param rw RW对象
     */
    @Transactional
    public void refreshWindows(TornFactionRwDO rw) {
        LocalDateTime observedAt = rw.getEndTime() == null ? LocalDateTime.now() : rw.getEndTime();
        List<AttackTimeWindowDO> candidates = attackLogDao.queryActiveTimeWindows(
                rw.getFactionId(), rw.getOpponentFactionId(), WINDOW_MINUTES, MIN_BATTLE_COUNT,
                rw.getStartTime(), observedAt);
        if (CollectionUtils.isEmpty(candidates)) {
            return;
        }

        List<TornFactionRwStatWindowDO> existingWindows = new ArrayList<>(findWindows(rw.getId()));
        for (AttackTimeWindowDO candidate : candidates) {
            persistCandidate(rw, candidate, observedAt, existingWindows);
        }
    }

    /**
     * 查询RW窗口目录。
     *
     * @param rw RW对象
     * @return 窗口目录
     */
    public List<RwStatWindowVO> queryCatalog(TornFactionRwDO rw) {
        return windowDao.queryWindowCatalog(rw.getId(), rw.getFactionId(), rw.getOpponentFactionId());
    }

    /**
     * 查询指定窗口目录项。
     *
     * @param rw RW对象
     * @param windowCode 窗口字母
     * @return 窗口目录项，不存在时返回null
     */
    public RwStatWindowVO queryWindow(TornFactionRwDO rw, String windowCode) {
        return queryCatalog(rw).stream()
                .filter(window -> windowCode.equals(window.getWindowCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询最近一个已确认且双方均有有效进攻的窗口。
     *
     * @param rw RW对象
     * @return 最近有效窗口，不存在时返回null
     */
    public RwStatWindowVO queryLatestConfirmedWindow(TornFactionRwDO rw) {
        TornFactionRwStatWindowDO window = windowDao.queryLatestConfirmedWindow(
                rw.getId(), rw.getFactionId(), rw.getOpponentFactionId());
        if (window == null) {
            return null;
        }
        return toWindowVO(window);
    }

    /**
     * 查询指定窗口的双方用户出手统计。
     *
     * @param rw RW对象
     * @param window 窗口目录项
     * @return 双方统计摘要
     */
    public RwAttackFrequencySummaryVO queryFrequency(TornFactionRwDO rw, RwStatWindowVO window) {
        List<RwUserAttackStatVO> users = windowDao.queryUserAttackStats(
                window.getStartTime(), window.getEndTime(), rw.getFactionId(), rw.getOpponentFactionId());
        List<RwUserAttackStatVO> selfUsers = filterUsers(users, rw.getFactionId());
        List<RwUserAttackStatVO> opponentUsers = filterUsers(users, rw.getOpponentFactionId());

        RwAttackFrequencySummaryVO summary = new RwAttackFrequencySummaryVO();
        summary.setWindow(window);
        summary.setSelfUsers(selfUsers);
        summary.setOpponentUsers(opponentUsers);
        summary.setSelfUserCount(selfUsers.size());
        summary.setOpponentUserCount(opponentUsers.size());
        summary.setSelfAttackCount(sumAttackCount(selfUsers));
        summary.setOpponentAttackCount(sumAttackCount(opponentUsers));
        return summary;
    }

    private void persistCandidate(TornFactionRwDO rw, AttackTimeWindowDO candidate,
                                 LocalDateTime observedAt, List<TornFactionRwStatWindowDO> existingWindows) {
        if (findConfirmedOverlap(candidate, existingWindows) != null) {
            return;
        }

        TornFactionRwStatWindowDO unconfirmedWindow = findUnconfirmedOverlap(candidate, existingWindows);
        boolean confirmed = isConfirmed(rw, candidate, observedAt);
        if (unconfirmedWindow != null) {
            windowDao.updateUnconfirmedWindow(toWindow(unconfirmedWindow, candidate), confirmed);
            unconfirmedWindow.setStartTime(candidate.start());
            unconfirmedWindow.setEndTime(candidate.end());
            unconfirmedWindow.setConfirmed(confirmed);
            return;
        }

        TornFactionRwStatWindowDO newWindow = new TornFactionRwStatWindowDO();
        newWindow.setRwId(rw.getId());
        newWindow.setWindowCode(nextWindowCode(existingWindows));
        newWindow.setStartTime(candidate.start());
        newWindow.setEndTime(candidate.end());
        newWindow.setConfirmed(confirmed);
        if (windowDao.insertIgnoreConflict(newWindow) > 0) {
            existingWindows.add(newWindow);
        }
    }

    private List<TornFactionRwStatWindowDO> findWindows(long rwId) {
        return windowDao.queryActiveWindows(rwId);
    }

    private TornFactionRwStatWindowDO findConfirmedOverlap(AttackTimeWindowDO candidate,
                                                            List<TornFactionRwStatWindowDO> windows) {
        return windows.stream()
                .filter(window -> Boolean.TRUE.equals(window.getConfirmed()))
                .filter(window -> overlaps(window.getStartTime(), window.getEndTime(), candidate.start(), candidate.end()))
                .findFirst()
                .orElse(null);
    }

    private TornFactionRwStatWindowDO findUnconfirmedOverlap(AttackTimeWindowDO candidate,
                                                              List<TornFactionRwStatWindowDO> windows) {
        return windows.stream()
                .filter(window -> !Boolean.TRUE.equals(window.getConfirmed()))
                .filter(window -> overlaps(window.getStartTime(), window.getEndTime(), candidate.start(), candidate.end()))
                .findFirst()
                .orElse(null);
    }

    private boolean overlaps(LocalDateTime leftStart, LocalDateTime leftEnd,
                             LocalDateTime rightStart, LocalDateTime rightEnd) {
        return !leftEnd.isBefore(rightStart) && !rightEnd.isBefore(leftStart);
    }

    private boolean isConfirmed(TornFactionRwDO rw, AttackTimeWindowDO candidate, LocalDateTime observedAt) {
        return rw.getEndTime() != null || !candidate.end().plusMinutes(WINDOW_MINUTES).isAfter(observedAt);
    }

    private String nextWindowCode(List<TornFactionRwStatWindowDO> windows) {
        long maxSequence = windows.stream()
                .map(TornFactionRwStatWindowDO::getWindowCode)
                .mapToLong(RwStatWindowCodeUtils::toSequence)
                .max()
                .orElse(0L);
        return RwStatWindowCodeUtils.toCode(maxSequence + 1);
    }

    private TornFactionRwStatWindowDO toWindow(TornFactionRwStatWindowDO source, AttackTimeWindowDO candidate) {
        source.setStartTime(candidate.start());
        source.setEndTime(candidate.end());
        return source;
    }

    private RwStatWindowVO toWindowVO(TornFactionRwStatWindowDO window) {
        RwStatWindowVO result = new RwStatWindowVO();
        result.setRwId(window.getRwId());
        result.setWindowCode(window.getWindowCode());
        result.setStartTime(window.getStartTime());
        result.setEndTime(window.getEndTime());
        result.setConfirmed(window.getConfirmed());
        return result;
    }

    private List<RwUserAttackStatVO> filterUsers(List<RwUserAttackStatVO> users, long factionId) {
        return users.stream()
                .filter(user -> Objects.equals(user.getAttackFactionId(), factionId))
                .toList();
    }

    private int sumAttackCount(List<RwUserAttackStatVO> users) {
        return users.stream().mapToInt(RwUserAttackStatVO::getAttackCount).sum();
    }
}
