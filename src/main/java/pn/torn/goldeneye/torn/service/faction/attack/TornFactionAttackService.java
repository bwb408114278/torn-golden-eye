package pn.torn.goldeneye.torn.service.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
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

/**
 * 帮派攻击记录逻辑类
 *
 * @author Bai
 * @version 1.3.8
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
     * 解析新闻列表为攻击记录。
     * <p>
     * 已存在的攻击仅跳过DO创建和落库, 其非空日志Code仍必须收集,
     * 且其攻守昵称必须恢复到userNameMap, 保证重叠重试窗口内日志服务
     * 重抓时能用既有昵称还原事实文本, 不产生"Someone"伪造日志。
     *
     * @param now         当前时间, 用于计算守方在线状态
     * @param resp        攻击记录响应
     * @param userMap     双方帮派成员在线状态映射
     * @param logIdSet    本轮攻击日志Code收集集合
     * @param userNameMap 攻守双方用户ID到昵称映射, 供日志抓取补齐昵称
     * @param eloMap      用户ID到ELO映射
     * @return 待新建的攻击记录列表, 已存在ID不在其中
     */
    public List<TornFactionAttackDO> parseAttackList(LocalDateTime now, TornFactionAttackRespVO resp,
                                                     Map<Long, TornFactionMemberVO> userMap, Set<String> logIdSet,
                                                     Map<Long, String> userNameMap, Map<Long, Integer> eloMap) {
        if (resp == null || CollectionUtils.isEmpty(resp.getAttacks())) {
            return new ArrayList<>();
        }

        List<Long> idList = resp.getAttacks().stream().map(TornFactionAttackVO::getId).toList();
        Map<Long, TornFactionAttackDO> existingAttackMap = new HashMap<>();
        for (TornFactionAttackDO existing : attackDao.lambdaQuery().in(TornFactionAttackDO::getId, idList).list()) {
            existingAttackMap.put(existing.getId(), existing);
        }

        List<TornFactionAttackDO> attackList = new ArrayList<>();
        for (TornFactionAttackVO attack : resp.getAttacks()) {
            collectAttackLogId(attack, logIdSet);
            TornFactionAttackDO existing = existingAttackMap.get(attack.getId());
            if (existing != null) {
                populateUserNameMap(existing, userNameMap);
                continue;
            }

            TornFactionAttackDO data = parseNews(now, attack, userMap, eloMap);
            attackList.add(data);

            existingAttackMap.put(data.getId(), data);
            populateUserNameMap(data, userNameMap);
        }

        return attackList;
    }

    /**
     * 将攻击记录的攻守双方用户ID到昵称映射写入userNameMap。
     * <p>
     * 新建与已存在攻击必须共用本方法, 保证重叠重试时Torn API缺失的参与者昵称
     * 能从既有昵称映射恢复, 战斗日志文本不回退为"Someone"伪造事实。
     *
     * @param attack      攻击记录
     * @param userNameMap 攻守双方用户ID到昵称映射, 供日志抓取补齐昵称
     */
    private void populateUserNameMap(TornFactionAttackDO attack, Map<Long, String> userNameMap) {
        userNameMap.put(attack.getAttackUserId(), attack.getAttackUserNickname());
        userNameMap.put(attack.getDefendUserId(), attack.getDefendUserNickname());
    }

    /**
     * 收集非空攻击日志Code。
     * <p>
     * 该收集在已存在攻击跳过判断之前执行, 不得因攻击已落库而遗漏。
     *
     * @param attack   单条攻击响应
     * @param logIdSet 日志Code收集集合
     */
    private void collectAttackLogId(TornFactionAttackVO attack, Set<String> logIdSet) {
        if (StringUtils.hasText(attack.getCode())) {
            logIdSet.add(attack.getCode());
        }
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