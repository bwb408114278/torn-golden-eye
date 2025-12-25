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
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserProfileVO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserEloDTO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserEloVO;
import pn.torn.goldeneye.torn.model.user.elo.TornUserStatsVO;
import pn.torn.goldeneye.torn.service.data.TornAttackLogService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    public void spiderAttackData(TornSettingFactionDO faction, LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionAttackDTO param;
        LocalDateTime queryFrom = from;
        TornFactionAttackRespVO resp;
        List<TornFactionAttackDO> attackList;
        Map<Long, TornUserProfileVO> userMap = new ConcurrentHashMap<>();
        Map<Long, TornUserStatsVO> eloMap = new ConcurrentHashMap<>();
        Set<String> logIdSet = new HashSet<>();
        Map<Long, String> userNameMap = new HashMap<>();

        do {
            param = new TornFactionAttackDTO(queryFrom, to, limit);
            resp = tornApi.sendRequest(faction.getId(), param, TornFactionAttackRespVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getAttacks())) {
                break;
            }

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

        attackLogService.saveAttackLog(faction.getId(), logIdSet, userNameMap);
    }

    /**
     * 解析新闻列表为攻击记录
     */
    public List<TornFactionAttackDO> parseNewsList(TornFactionAttackRespVO resp, Map<Long, TornUserProfileVO> userMap,
                                                   Set<String> logIdSet, Map<Long, String> userNameMap,
                                                   Map<Long, TornUserStatsVO> eloMap) {
        if (resp == null || CollectionUtils.isEmpty(resp.getAttacks())) {
            return new ArrayList<>();
        }

        List<Long> idList = resp.getAttacks().stream().map(TornFactionAttackVO::getId).toList();
        Set<Long> existingIds = attackDao.lambdaQuery().in(TornFactionAttackDO::getId, idList).list().stream()
                .map(TornFactionAttackDO::getId).collect(Collectors.toSet());

        List<CompletableFuture<TornFactionAttackDO>> futureList = resp.getAttacks().stream()
                .filter(attack -> !existingIds.contains(attack.getId()))
                .distinct()
                .map(attack ->
                        CompletableFuture.supplyAsync(() ->
                                parseNews(attack, userMap, eloMap), virtualThreadExecutor))
                .toList();

        List<TornFactionAttackDO> attackList = futureList.stream()
                .map(CompletableFuture::join)
                .toList();
        attackList.forEach(data -> {
            logIdSet.add(data.getAttackLogId());
            userNameMap.put(data.getAttackUserId(), data.getAttackUserNickname());
            userNameMap.put(data.getDefendUserId(), data.getDefendUserNickname());
        });
        return attackList;
    }

    /**
     * 解析单条新闻为攻击记录
     */
    public TornFactionAttackDO parseNews(TornFactionAttackVO attack, Map<Long, TornUserProfileVO> userMap,
                                         Map<Long, TornUserStatsVO> eloMap) {
        TornFactionAttackDO data = new TornFactionAttackDO();
        data.setId(attack.getId());
        data.setDefendUserId(attack.getDefender().getId());
        data.setDefendUserNickname(attack.getDefender().getName());
        data.setDefendUserOnlineStatus(extractOnlineStatus(data.getDefendUserId(), userMap));
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

        data.setAttackerElo(extractElo(data.getAttackUserId(), data.getAttackEndTime(), eloMap));
        data.setDefenderElo(extractElo(data.getDefendUserId(), data.getAttackEndTime(), eloMap));

        return data;
    }

    /**
     * 提取在线状态
     */
    private String extractOnlineStatus(long userId, Map<Long, TornUserProfileVO> userMap) {
        if (userId == 0L) {
            return "";
        }

        TornUserProfileVO user = userMap.get(userId);
        if (user != null) {
            return user.getLastAction().getStatus();
        }

        TornUserVO resp = tornApi.sendRequest(new TornUserDTO(userId), TornUserVO.class);
        if (resp != null && resp.getProfile() != null) {
            user = resp.getProfile();
            userMap.put(userId, user);
            return user.getLastAction().getStatus();
        }

        return null;
    }

    /**
     * 提取Elo
     */
    private Integer extractElo(long userId, LocalDateTime time, Map<Long, TornUserStatsVO> eloMap) {
        if (userId == 0L) {
            return null;
        }

        TornUserStatsVO elo = eloMap.get(userId);
        if (elo != null) {
            return elo.getValue();
        }

        TornUserEloVO resp = tornApi.sendRequest(new TornUserEloDTO(userId, time), TornUserEloVO.class);
        if (resp != null && !CollectionUtils.isEmpty(resp.getPersonalstats())) {
            TornUserStatsVO stats = resp.getPersonalstats().stream()
                    .filter(p -> p.getName().equals("elo")).findAny()
                    .orElse(null);
            if (stats != null) {
                eloMap.put(userId, stats);
                return stats.getValue();
            }
        }

        return null;
    }
}