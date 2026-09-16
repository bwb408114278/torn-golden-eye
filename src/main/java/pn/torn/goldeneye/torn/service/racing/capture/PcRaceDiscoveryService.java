package pn.torn.goldeneye.torn.service.racing.capture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.racing.TornRacingParticipantDAO;
import pn.torn.goldeneye.repository.dao.racing.TornRacingRaceDAO;
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceDetailVO;
import pn.torn.goldeneye.torn.model.user.racing.TornUserRaceDTO;
import pn.torn.goldeneye.torn.model.user.racing.TornUserRacesVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * SMTHPC赛事定位逻辑层。
 *
 * <p>按优先级候选链逐个调用赛车列表接口定位目标业务日期的赛事；列表接口自带全量成绩，命中即返回完整赛事，
 * 无需再调详情接口。每个候选的Key在finally中归还，命中与异常路径同样归还。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PcRaceDiscoveryService {
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornRacingRaceDAO raceDao;
    private final TornRacingParticipantDAO participantDao;

    /**
     * 按优先级候选链定位目标业务日期的赛事。
     *
     * @param businessDate 目标业务日期
     * @return 含全量成绩的赛事详情；全部候选未命中时返回null
     */
    public TornRaceDetailVO findRace(LocalDate businessDate) {
        for (Long userId : buildCandidateUserIds()) {
            TornRaceDetailVO race = findRaceByCandidate(userId, businessDate);
            if (race != null) {
                return race;
            }
        }
        return null;
    }

    /**
     * 构建保序去重的候选用户ID列表。
     *
     * <p>候选顺序为常任创建人、最近一场已抓取赛事的参赛选手、参赛主力帮派的Key持有人、其余Key持有人，
     * 去重保序后截断到扫描上限。</p>
     *
     * @return 候选用户ID列表
     */
    List<Long> buildCandidateUserIds() {
        List<Long> candidateList = new ArrayList<>();
        candidateList.add(RacingConstants.CREATOR_USER_ID);
        candidateList.addAll(queryRecentParticipantUserIds());

        List<TornApiKeyDO> keyList = apiKeyConfig.getAllEnableKeys();
        keyList.stream()
                .filter(key -> isMainFactionKey(key.getFactionId()))
                .map(TornApiKeyDO::getUserId)
                .forEach(candidateList::add);
        keyList.stream()
                .filter(key -> !isMainFactionKey(key.getFactionId()))
                .map(TornApiKeyDO::getUserId)
                .forEach(candidateList::add);

        return candidateList.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(RacingConstants.DISCOVERY_SCAN_LIMIT)
                .toList();
    }

    /**
     * 使用单个候选用户定位赛事，Key不可用或调用异常时返回null。
     *
     * @param userId       候选用户ID
     * @param businessDate 目标业务日期
     * @return 命中的赛事详情；未命中时返回null
     */
    private TornRaceDetailVO findRaceByCandidate(long userId, LocalDate businessDate) {
        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(userId);
        if (key == null) {
            return null;
        }

        try {
            TornUserRacesVO resp = tornApi.sendRequest(
                    new TornUserRaceDTO(RacingConstants.DISCOVERY_PAGE_SIZE), key, TornUserRacesVO.class);
            return matchRace(resp, businessDate);
        } catch (Exception e) {
            log.warn("SMTHPC赛事定位候选调用失败, userId={}", userId, e);
            return null;
        } finally {
            apiKeyConfig.returnKey(key);
        }
    }

    /**
     * 在列表响应中匹配赛事名称与目标业务日期。
     *
     * @param resp         赛车列表响应
     * @param businessDate 目标业务日期
     * @return 命中的赛事详情；未命中时返回null
     */
    private TornRaceDetailVO matchRace(TornUserRacesVO resp, LocalDate businessDate) {
        if (resp == null || CollectionUtils.isEmpty(resp.getRaces())) {
            return null;
        }

        for (TornRaceDetailVO race : resp.getRaces()) {
            if (!RacingConstants.RACE_TITLE.equalsIgnoreCase(race.getTitle()) || race.getSchedule() == null) {
                continue;
            }
            if (businessDate.equals(DateTimeUtils.convertToTornDate(race.getSchedule().getStart()))) {
                return race;
            }
        }
        return null;
    }

    /**
     * 查询最近一场已抓取赛事的参赛选手，作为命中率最高的动态候选。
     *
     * @return 按原始名次升序、缺失名次靠后的选手用户ID；无历史赛事时返回空列表
     */
    private List<Long> queryRecentParticipantUserIds() {
        TornRacingRaceDO latestRace = raceDao.queryLatestRace();
        if (latestRace == null) {
            return List.of();
        }

        return participantDao.queryByRaceId(latestRace.getRaceId()).stream()
                .sorted(Comparator.comparing(TornRacingParticipantDO::getPosition,
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder())))
                .map(TornRacingParticipantDO::getUserId)
                .toList();
    }

    /**
     * 判断Key持有者是否属于参赛主力帮派。
     *
     * @param factionId Key快照帮派ID
     * @return true表示属于参赛主力帮派
     */
    private boolean isMainFactionKey(Long factionId) {
        if (factionId == null) {
            return false;
        }
        return factionId == TornConstants.FACTION_PN_ID || factionId == TornConstants.FACTION_CCRC_ID;
    }
}
