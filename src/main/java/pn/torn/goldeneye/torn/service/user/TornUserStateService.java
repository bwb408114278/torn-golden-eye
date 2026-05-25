package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserStateSnapshotDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.user.TornUserStateSnapshotDO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserEloDTO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserEloVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 用户状态逻辑类
 *
 * @author Bai
 * @version 1.1.5
 * @since 2026.05.22
 */
@Service
@RequiredArgsConstructor
public class TornUserStateService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornFactionRwDAO rwDao;
    private final TornUserStateSnapshotDAO stateDao;

    /**
     * 定时任务：每天8:30预拉取今天开始RW且未结束帮派，双方玩家的ELO
     */
    @Scheduled(cron = "0 30 8 * * *")
    public void scheduledFetchElo() {
        LocalDate today = LocalDate.now();
        List<TornFactionRwDO> rwList = rwDao.lambdaQuery().isNull(TornFactionRwDO::getEndTime).list();
        List<Long> allUserIds = new ArrayList<>();
        for (TornFactionRwDO rw : rwList) {
            if (!rw.getStartTime().toLocalDate().isBefore(today)) {
                allUserIds.addAll(getFactionMemberList(rw.getFactionId()));
                allUserIds.addAll(getFactionMemberList(rw.getOpponentFactionId()));
            }
        }

        getEloMap(allUserIds, LocalDate.now());
    }

    /**
     * 批量获取ELO，优先读库，缺失的再实时拉取并存库
     */
    public Map<Long, Integer> getEloMap(Collection<Long> userIds, LocalDate date) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Map.of();
        }

        List<TornUserStateSnapshotDO> existing = stateDao.lambdaQuery()
                .in(TornUserStateSnapshotDO::getUserId, userIds)
                .eq(TornUserStateSnapshotDO::getRecordDate, date)
                .list();
        Map<Long, Integer> result = existing.stream()
                .collect(Collectors.toMap(TornUserStateSnapshotDO::getUserId, TornUserStateSnapshotDO::getElo));

        Set<Long> missing = userIds.stream()
                .filter(id -> id != 0 && !result.containsKey(id))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            fetchAndSave(missing, date, result);
        }

        return result;
    }

    /**
     * 通过API读取ELO数据并存入数据库，以及写入传入的Map
     */
    private void fetchAndSave(Set<Long> userIds, LocalDate date, Map<Long, Integer> result) {
        LocalDateTime queryTime = date.atTime(10, 0, 0);
        List<TornUserStateSnapshotDO> toSave = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> futures = userIds.stream()
                .map(userId -> CompletableFuture.runAsync(() -> {
                    TornUserEloVO resp = tornApi.sendRequest(new TornUserEloDTO(userId, queryTime), TornUserEloVO.class);
                    if (resp != null && !CollectionUtils.isEmpty(resp.getPersonalstats())) {
                        resp.getPersonalstats().stream()
                                .filter(p -> p.getName().equals("elo"))
                                .findAny()
                                .ifPresent(stats -> {
                                    result.put(userId, stats.getValue());

                                    TornUserStateSnapshotDO data = new TornUserStateSnapshotDO();
                                    data.setUserId(userId);
                                    data.setRecordDate(date);
                                    data.setElo(stats.getValue());
                                    toSave.add(data);
                                });
                    }
                }, virtualThreadExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        if (!toSave.isEmpty()) {
            stateDao.saveBatch(toSave);
        }
    }

    /**
     * 获取帮派成员列表
     */
    private List<Long> getFactionMemberList(long factionId) {
        TornFactionMemberDTO param = new TornFactionMemberDTO(factionId);
        TornFactionMemberListVO resp = tornApi.sendRequest(param, TornFactionMemberListVO.class);

        if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
            return List.of();
        }

        return resp.getMembers().stream().map(TornFactionMemberVO::getId).toList();
    }
}