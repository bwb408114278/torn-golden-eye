package pn.torn.goldeneye.torn.service.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionAttackDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackDTO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackRespVO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.service.data.TornAttackLogService;
import pn.torn.goldeneye.torn.service.user.TornUserStateService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 帮派攻击记录逻辑类
 *
 * @author Bai
 * @version 1.1.5
 * @since 2025.12.18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornFactionAttackService {
    private final TornApi tornApi;
    private final TornAttackLogService attackLogService;
    private final TornUserStateService userStateService;
    private final TornFactionAttackDAO attackDao;

    /**
     * 爬取攻击记录
     */
    public Collection<TornFactionMemberVO> spiderAttackData(TornSettingFactionDO faction,
                                                            long opponentFactionId,
                                                            LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionAttackDTO param;
        LocalDateTime queryFrom = from;
        TornFactionAttackRespVO resp;
        List<TornFactionAttackDO> attackList;
        Set<String> logIdSet = new HashSet<>();
        Map<Long, String> userNameMap = new HashMap<>();
        Map<Long, TornFactionMemberVO> userMap = extractOnlineStatus(faction.getId(), opponentFactionId);
        Map<Long, Integer> eloMap = userStateService.getEloMap(userMap.keySet(), from.toLocalDate());

        do {
            param = new TornFactionAttackDTO(queryFrom, to, limit);
            resp = tornApi.sendRequest(faction.getId(), param, TornFactionAttackRespVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getAttacks())) {
                break;
            }

            attackList = parseAttackList(LocalDateTime.now(), resp, userMap, logIdSet, userNameMap, eloMap);
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
        return userMap.values();
    }

    /**
     * 解析新闻列表为攻击记录
     */
    public List<TornFactionAttackDO> parseAttackList(LocalDateTime now, TornFactionAttackRespVO resp,
                                                     Map<Long, TornFactionMemberVO> userMap, Set<String> logIdSet,
                                                     Map<Long, String> userNameMap, Map<Long, Integer> eloMap) {
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

            TornFactionAttackDO data = parseNews(now, attack, userMap, eloMap);
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
    public TornFactionAttackDO parseNews(LocalDateTime now, TornFactionAttackVO attack,
                                         Map<Long, TornFactionMemberVO> userMap, Map<Long, Integer> eloMap) {
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

        TornFactionMemberVO la = userMap.get(data.getDefendUserId());
        boolean isOffline = la == null ||
                DateTimeUtils.isIntervalAtLeast(la.getLastAction().getTimestamp(), now, 2, TimeUnit.MINUTES);
        data.setDefendUserOnlineStatus(isOffline ? TornConstants.USER_STATUS_OFFLINE : TornConstants.USER_STATUS_ONLINE);
        data.setAttackerElo(eloMap.get(data.getAttackUserId()));
        data.setDefenderElo(eloMap.get(data.getDefendUserId()));

        return data;
    }

    /**
     * 提取在线状态
     */
    private Map<Long, TornFactionMemberVO> extractOnlineStatus(long... factionIds) {
        if (factionIds == null) {
            return Map.of();
        }

        Map<Long, TornFactionMemberVO> resultMap = new LinkedHashMap<>();
        for (long factionId : factionIds) {
            TornFactionMemberDTO param = new TornFactionMemberDTO(factionId);
            TornFactionMemberListVO resp = tornApi.sendRequest(param, TornFactionMemberListVO.class);

            if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
                continue;
            }

            for (TornFactionMemberVO member : resp.getMembers()) {
                resultMap.put(member.getId(), member);
            }
        }

        return resultMap;
    }
}