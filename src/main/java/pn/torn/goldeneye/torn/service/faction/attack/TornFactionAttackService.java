package pn.torn.goldeneye.torn.service.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionAttackDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackDTO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackRespVO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserEloDTO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserEloVO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserStatsVO;
import pn.torn.goldeneye.torn.service.data.TornAttackLogService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 帮派攻击记录逻辑类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornFactionAttackService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornAttackLogService attackLogService;
    private final TornFactionAttackDAO attackDao;

    /**
     * 爬取攻击记录
     */
    public void spiderAttackData(TornSettingFactionDO faction, long opponentFactionId,
                                 LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionAttackDTO param;
        LocalDateTime queryFrom = from;
        TornFactionAttackRespVO resp;
        List<TornFactionAttackDO> attackList;
        Set<String> logIdSet = new HashSet<>();
        Map<Long, String> userNameMap = new HashMap<>();
        Map<Long, TornUserLastActionVO> userMap = extractOnlineStatus(faction.getId(), opponentFactionId);
        Map<Long, TornUserStatsVO> eloMap = new HashMap<>();

        do {
            param = new TornFactionAttackDTO(queryFrom, to, limit);
            resp = tornApi.sendRequest(faction.getId(), param, TornFactionAttackRespVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getAttacks())) {
                break;
            }

            extractElo(resp, eloMap);
            attackList = parseNewsList(resp, userMap, logIdSet, userNameMap, eloMap);
            if (!CollectionUtils.isEmpty(attackList)) {
                attackDao.saveBatch(attackList);
            }

            queryFrom = DateTimeUtils.convertToDateTime(resp.getAttacks().getLast().getEnded());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (resp.getAttacks().size() >= limit);

        attackLogService.saveAttackLog(faction.getId(), logIdSet, userNameMap, eloMap);
    }

    /**
     * 解析新闻列表为攻击记录
     */
    public List<TornFactionAttackDO> parseNewsList(TornFactionAttackRespVO resp, Map<Long, TornUserLastActionVO> userMap,
                                                   Set<String> logIdSet, Map<Long, String> userNameMap,
                                                   Map<Long, TornUserStatsVO> eloMap) {
        if (resp == null || CollectionUtils.isEmpty(resp.getAttacks())) {
            return new ArrayList<>();
        }

        List<Long> idList = resp.getAttacks().stream().map(TornFactionAttackVO::getId).toList();
        Set<Long> existingIds = attackDao.lambdaQuery().in(TornFactionAttackDO::getId, idList).list().stream()
                .map(TornFactionAttackDO::getId).collect(Collectors.toSet());

        List<TornFactionAttackDO> attackList = new ArrayList<>();
        for (TornFactionAttackVO attack : resp.getAttacks()) {
            if (existingIds.contains(attack.getId())) {
                continue;
            }

            TornFactionAttackDO data = parseNews(attack, userMap, eloMap);
            attackList.add(data);

            existingIds.add(data.getId());
            logIdSet.add(data.getAttackLogId());
            userNameMap.put(data.getAttackUserId(), data.getAttackUserNickname());
            userNameMap.put(data.getDefendUserId(), data.getDefendUserNickname());
        }

        return attackList;
    }

    /**
     * 解析单条新闻为攻击记录
     */
    public TornFactionAttackDO parseNews(TornFactionAttackVO attack, Map<Long, TornUserLastActionVO> userMap,
                                         Map<Long, TornUserStatsVO> eloMap) {
        TornFactionAttackDO data = new TornFactionAttackDO();
        data.setId(attack.getId());
        data.setDefendUserId(attack.getDefender().getId());
        data.setDefendUserNickname(attack.getDefender().getName());
        data.setAttackStartTime(DateTimeUtils.convertToDateTime(attack.getStarted()));
        data.setAttackEndTime(DateTimeUtils.convertToDateTime(attack.getEnded()));
        data.setAttackResult(attack.getResult());
        data.setAttackLogId(attack.getCode());
        data.setRespectGain(attack.getRespectGain());
        data.setRespectLoss(attack.getRespectLoss());
        data.setChain(attack.getChain());
        data.setIsInterrupted(attack.getIsInterrupted());
        data.setIsStealth(attack.getIsStealth());
        data.setIsRaid(attack.getIsRaid());
        data.setIsRankedWar(attack.getIsRankedWar());
        data.setModifierFairFight(attack.getModifiers().getFairFight());
        data.setModifierWar(attack.getModifiers().getWar());
        data.setModifierRetaliation(attack.getModifiers().getRetaliation());
        data.setModifierGroup(attack.getModifiers().getGroup());
        data.setModifierOversea(attack.getModifiers().getOverseas());
        data.setModifierChain(attack.getModifiers().getChain());
        data.setModifierWarlord(attack.getModifiers().getWarlord());

        if (attack.getAttacker() == null) {
            data.setAttackUserId(0L);
            data.setAttackUserNickname("Someone");
            data.setAttackFactionId(0L);
            data.setAttackFactionName("");
        } else {
            data.setAttackUserId(attack.getAttacker().getId());
            data.setAttackUserNickname(attack.getAttacker().getName());

            if (attack.getAttacker().getFaction() != null) {
                data.setAttackFactionId(attack.getAttacker().getFaction().getId());
                data.setAttackFactionName(attack.getAttacker().getFaction().getName());
            }
        }

        if (attack.getDefender().getFaction() != null) {
            data.setDefendFactionId(attack.getDefender().getFaction().getId());
            data.setDefendFactionName(attack.getDefender().getFaction().getName());
        }

        TornUserLastActionVO la = userMap.get(data.getDefendUserId());
        data.setDefendUserOnlineStatus(la == null ? "Offline" : la.getStatus());

        TornUserStatsVO attackerStats = eloMap.get(data.getAttackUserId());
        TornUserStatsVO defenderStats = eloMap.get(data.getDefendUserId());
        data.setAttackerElo(attackerStats == null ? null : attackerStats.getValue());
        data.setDefenderElo(defenderStats == null ? null : defenderStats.getValue());

        return data;
    }

    /**
     * 提取在线状态
     */
    private Map<Long, TornUserLastActionVO> extractOnlineStatus(long... factionIds) {
        if (factionIds == null) {
            return Map.of();
        }

        Map<Long, TornUserLastActionVO> resultMap = new LinkedHashMap<>();
        for (long factionId : factionIds) {
            TornFactionMemberDTO param = new TornFactionMemberDTO(factionId);
            TornFactionMemberListVO resp = tornApi.sendRequest(param, TornFactionMemberListVO.class);

            if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
                continue;
            }

            for (TornFactionMemberVO member : resp.getMembers()) {
                resultMap.put(member.getId(), member.getLastAction());
            }
        }

        return resultMap;
    }

    /**
     * 提取Elo
     */
    private void extractElo(TornFactionAttackRespVO attackResp, Map<Long, TornUserStatsVO> eloMap) {
        Set<String> userIdSet = new HashSet<>();
        for (TornFactionAttackVO attack : attackResp.getAttacks()) {
            LocalDate localDate = DateTimeUtils.convertToDateTime(attack.getStarted()).toLocalDate();
            String key = DateTimeUtils.convertToString(localDate);

            userIdSet.add(attack.getAttacker() == null ? "" : attack.getAttacker().getId() + "#" + key);
            userIdSet.add(attack.getDefender() == null ? "" : attack.getDefender().getId() + "#" + key);
        }
        userIdSet.removeIf(String::isEmpty);

        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (String key : userIdSet) {
            String[] keys = key.split("#");
            long userId = Long.parseLong(keys[0]);
            LocalDateTime time = DateTimeUtils.convertToDate(keys[1]).atTime(10, 0, 0);
            if (eloMap.containsKey(userId)) {
                continue;
            }

            futureList.add(CompletableFuture.runAsync(() -> {
                TornUserEloVO resp = tornApi.sendRequest(new TornUserEloDTO(userId, time), TornUserEloVO.class);
                if (resp != null && !CollectionUtils.isEmpty(resp.getPersonalstats())) {
                    resp.getPersonalstats().stream()
                            .filter(p -> p.getName().equals("elo"))
                            .findAny()
                            .ifPresent(stats -> eloMap.put(userId, stats));
                }
            }, virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
    }
}