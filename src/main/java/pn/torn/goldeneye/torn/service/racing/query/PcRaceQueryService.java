package pn.torn.goldeneye.torn.service.racing.query;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.repository.dao.racing.TornRacingParticipantDAO;
import pn.torn.goldeneye.repository.dao.racing.TornRacingRaceDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceResultBO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceScoreBO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * PC赛车榜单与个人成绩查询逻辑层。
 *
 * <p>SMTH名次、榜单顺序、最快圈、参赛率与抽奖池的口径只在本类实现一次，
 * 所有展示入口共用 {@code buildResult}，不得在展示类中重复实现。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Service
@RequiredArgsConstructor
public class PcRaceQueryService {
    /**
     * 名次排序：API原始名次升序，缺失名次靠后，再按用户ID保证顺序稳定
     */
    private static final Comparator<TornRacingParticipantDO> POSITION_COMPARATOR =
            Comparator.comparing(TornRacingParticipantDO::getPosition,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(TornRacingParticipantDO::getUserId);
    /**
     * 参赛率百分比基数
     */
    private static final BigDecimal PERCENT_BASE = BigDecimal.valueOf(100);
    /**
     * 百分秒与秒的换算基数
     */
    private static final int HUNDREDTHS_PER_SECOND = 100;
    /**
     * 一小时的秒数
     */
    private static final int SECONDS_PER_HOUR = 3600;

    private final TornRacingRaceDAO raceDao;
    private final TornRacingParticipantDAO participantDao;
    private final TornUserDAO userDao;
    private final TornSettingFactionManager factionManager;
    private final PcRaceDrawCalculator drawCalculator;

    /**
     * 按业务日期构建榜单结果。
     *
     * @param businessDate 业务日期
     * @return 榜单结果；该业务日期无赛事时返回null
     */
    public PcRaceResultBO buildResultByBusinessDate(LocalDate businessDate) {
        return buildResult(raceDao.queryByBusinessDate(businessDate));
    }

    /**
     * 按赛事ID构建榜单结果。
     *
     * @param raceId Torn赛事ID
     * @return 榜单结果；赛事不存在时返回null
     */
    public PcRaceResultBO buildResultByRaceId(long raceId) {
        return buildResult(raceDao.queryByRaceId(raceId));
    }

    /**
     * 构建指定用户的近若干场家族赛事成绩。
     *
     * @param userId 目标选手Torn用户ID
     * @return 个人成绩；无记录时成绩条目为空列表
     */
    public PcRaceScoreBO buildScore(long userId) {
        List<TornRacingParticipantDO> recentList =
                participantDao.queryRecentAllianceByUser(userId, RacingConstants.SCORE_HISTORY_LIMIT);
        if (recentList.isEmpty()) {
            return new PcRaceScoreBO(userId, String.valueOf(userId), List.of());
        }

        List<Long> raceIdList = recentList.stream().map(TornRacingParticipantDO::getRaceId).distinct().toList();
        Map<Long, TornRacingRaceDO> raceMap = new HashMap<>();
        raceDao.queryByRaceIdList(raceIdList).forEach(race -> raceMap.put(race.getRaceId(), race));
        Map<Long, List<TornRacingParticipantDO>> allianceByRaceId = new HashMap<>();
        for (TornRacingParticipantDO participant : participantDao.queryByRaceIdList(raceIdList)) {
            if (Boolean.TRUE.equals(participant.getIsAlliance())) {
                allianceByRaceId.computeIfAbsent(participant.getRaceId(), key -> new ArrayList<>()).add(participant);
            }
        }

        Map<Long, Map<Long, Integer>> smthRankByRaceId = new HashMap<>();
        allianceByRaceId.forEach((raceId, allianceList) ->
                smthRankByRaceId.put(raceId, buildSmthRankMap(allianceList)));
        Map<Long, TornSettingFactionDO> factionMap = factionManager.getIdMap();
        String nickname = recentList.stream()
                .map(TornRacingParticipantDO::getNickname)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(String.valueOf(userId));

        List<PcRaceScoreBO.Item> itemList = recentList.stream()
                .filter(participant -> raceMap.containsKey(participant.getRaceId()))
                .sorted(Comparator.comparing(
                                (TornRacingParticipantDO participant) -> raceMap.get(participant.getRaceId()).getStartTime())
                        .reversed())
                .map(participant -> new PcRaceScoreBO.Item(
                        raceMap.get(participant.getRaceId()).getBusinessDate(),
                        toParticipantVO(participant,
                                smthRankByRaceId.getOrDefault(participant.getRaceId(), Map.of())
                                        .get(participant.getUserId()),
                                factionMap)))
                .toList();
        return new PcRaceScoreBO(userId, nickname, itemList);
    }

    /**
     * 按赛事主行构建榜单结果，三个查询入口共用此口径。
     *
     * @param race 赛事主行
     * @return 榜单结果；赛事主行为null时返回null
     */
    private PcRaceResultBO buildResult(TornRacingRaceDO race) {
        if (race == null) {
            return null;
        }

        List<TornRacingParticipantDO> participantList = participantDao.queryByRaceId(race.getRaceId());
        List<TornRacingParticipantDO> allianceList = participantList.stream()
                .filter(participant -> Boolean.TRUE.equals(participant.getIsAlliance()))
                .toList();
        List<TornRacingParticipantDO> uncrashedList = sortUncrashed(allianceList);
        List<TornRacingParticipantDO> crashedList = sortCrashed(allianceList);
        Map<Long, Integer> smthRankMap = buildSmthRankMap(allianceList);
        Map<Long, TornSettingFactionDO> factionMap = factionManager.getIdMap();

        List<PcRaceParticipantVO> participants = new ArrayList<>(allianceList.size());
        for (TornRacingParticipantDO participant : uncrashedList) {
            participants.add(toParticipantVO(participant, smthRankMap.get(participant.getUserId()), factionMap));
        }
        List<PcRaceParticipantVO> crashedVoList = new ArrayList<>(crashedList.size());
        for (TornRacingParticipantDO participant : crashedList) {
            PcRaceParticipantVO crashed = toParticipantVO(participant, null, factionMap);
            participants.add(crashed);
            crashedVoList.add(crashed);
        }

        List<PcRaceParticipantVO> drawPool = participants.stream()
                .filter(participant -> !participant.crashed())
                .toList();
        return new PcRaceResultBO(race.getRaceId(), race.getBusinessDate(), race.getTrackName(),
                race.getStartTime(), race.getCapturedTime(), participants,
                findFastestLap(uncrashedList, smthRankMap, factionMap),
                crashedVoList, allianceList.size(), participantList.size(),
                calcAllianceRate(allianceList.size(), participantList.size()),
                drawCalculator.draw(race.getRaceId(), drawPool),
                drawCalculator.drawNewcomer(race.getRaceId(),
                        buildNewcomerPool(participants, race.getStartTime())));
    }

    /**
     * 构建新人奖池：家族选手中注册时间晚于开赛时间减NEWCOMER_DAYS天者。
     *
     * <p>基准取该场赛事的开赛时间而非查询时刻，且注册时间是账号不可变属性，
     * 因此同一赛事的新人奖结果可被任何人在任何时候复现。注册时间为空者不具备资格。</p>
     *
     * @param participants 该场家族全员榜单（含撞车，顺序稳定）
     * @param startTime    开赛时间（北京时间）
     * @return 新人奖池；开赛时间缺失或选手列表为空时返回空列表
     */
    private List<PcRaceParticipantVO> buildNewcomerPool(List<PcRaceParticipantVO> participants,
                                                        LocalDateTime startTime) {
        if (startTime == null || participants.isEmpty()) {
            return List.of();
        }

        Map<Long, TornUserDO> userMap = userDao.queryUserMap(
                participants.stream().map(PcRaceParticipantVO::userId).toList());
        LocalDateTime threshold = startTime.minusDays(RacingConstants.NEWCOMER_DAYS);
        return participants.stream()
                .filter(participant -> isNewcomer(userMap.get(participant.userId()), threshold))
                .toList();
    }

    /**
     * 判断选手是否具备新人奖资格。
     *
     * @param user      选手的本地用户记录，本地无记录时为null
     * @param threshold 新人阈值（开赛时间减NEWCOMER_DAYS天）
     * @return true为具备资格
     */
    private boolean isNewcomer(TornUserDO user, LocalDateTime threshold) {
        return user != null && user.getRegisterTime() != null && user.getRegisterTime().isAfter(threshold);
    }

    /**
     * 计算家族未撞车选手按原始名次排序后的SMTH内部名次。
     *
     * @param allianceList 该场家族选手明细
     * @return Key为选手用户ID、Value为从1开始的SMTH名次
     */
    private Map<Long, Integer> buildSmthRankMap(List<TornRacingParticipantDO> allianceList) {
        List<TornRacingParticipantDO> uncrashedList = sortUncrashed(allianceList);
        Map<Long, Integer> smthRankMap = HashMap.newHashMap(uncrashedList.size());
        for (int index = 0; index < uncrashedList.size(); index++) {
            smthRankMap.put(uncrashedList.get(index).getUserId(), index + 1);
        }
        return smthRankMap;
    }

    /**
     * 按原始名次升序排列家族未撞车选手。
     *
     * @param allianceList 该场家族选手明细
     * @return 未撞车选手
     */
    private List<TornRacingParticipantDO> sortUncrashed(List<TornRacingParticipantDO> allianceList) {
        return allianceList.stream()
                .filter(participant -> !Boolean.TRUE.equals(participant.getHasCrashed()))
                .sorted(POSITION_COMPARATOR)
                .toList();
    }

    /**
     * 按原始名次升序排列撞车的家族选手，用于置底展示。
     *
     * @param allianceList 该场家族选手明细
     * @return 撞车选手
     */
    private List<TornRacingParticipantDO> sortCrashed(List<TornRacingParticipantDO> allianceList) {
        return allianceList.stream()
                .filter(participant -> Boolean.TRUE.equals(participant.getHasCrashed()))
                .sorted(POSITION_COMPARATOR)
                .toList();
    }

    /**
     * 在家族未撞车且圈速非空的选手中取最快圈。
     *
     * @param uncrashedList 该场家族未撞车选手
     * @param smthRankMap   SMTH名次映射
     * @param factionMap    帮派设置映射
     * @return 最快圈选手；无有效圈速时返回null
     */
    private PcRaceParticipantVO findFastestLap(List<TornRacingParticipantDO> uncrashedList,
                                               Map<Long, Integer> smthRankMap,
                                               Map<Long, TornSettingFactionDO> factionMap) {
        TornRacingParticipantDO fastestLap = uncrashedList.stream()
                .filter(participant -> participant.getBestLapTime() != null)
                .min(Comparator.comparing(TornRacingParticipantDO::getBestLapTime))
                .orElse(null);
        if (fastestLap == null) {
            return null;
        }

        return toParticipantVO(fastestLap, smthRankMap.get(fastestLap.getUserId()), factionMap);
    }

    /**
     * 将明细行转换为展示模型，并在此处一次性生成用时文本。
     *
     * @param participant 选手明细
     * @param smthRank    SMTH内部名次，撞车为null
     * @param factionMap  帮派设置映射
     * @return 选手展示模型
     */
    private PcRaceParticipantVO toParticipantVO(TornRacingParticipantDO participant, Integer smthRank,
                                                Map<Long, TornSettingFactionDO> factionMap) {
        TornSettingFactionDO faction = participant.getFactionId() == null
                ? null : factionMap.get(participant.getFactionId());
        return new PcRaceParticipantVO(participant.getUserId(), participant.getNickname(),
                faction == null ? null : faction.getFactionShortName(), smthRank, participant.getPosition(),
                formatRaceTime(participant.getRaceTime()), formatBestLapTime(participant.getBestLapTime()),
                Boolean.TRUE.equals(participant.getHasCrashed()));
    }

    /**
     * 计算家族参赛率，分子分母均含撞车选手。
     *
     * @param allianceCount 家族参赛人数
     * @param totalCount    全部参赛人数
     * @return 百分比，保留两位小数
     */
    private BigDecimal calcAllianceRate(int allianceCount, int totalCount) {
        if (totalCount == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        return BigDecimal.valueOf(allianceCount)
                .multiply(PERCENT_BASE)
                .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
    }

    /**
     * 将秒数格式化为HH:mm:ss.SS。
     *
     * @param seconds 秒数
     * @return 用时文本；秒数为null时返回null
     */
    private String formatRaceTime(BigDecimal seconds) {
        Long totalHundredths = toHundredths(seconds);
        if (totalHundredths == null) {
            return null;
        }

        long remain = totalHundredths;
        long hours = remain / (SECONDS_PER_HOUR * HUNDREDTHS_PER_SECOND);
        remain %= SECONDS_PER_HOUR * HUNDREDTHS_PER_SECOND;
        long minutes = remain / (60 * HUNDREDTHS_PER_SECOND);
        remain %= 60 * HUNDREDTHS_PER_SECOND;
        long secs = remain / HUNDREDTHS_PER_SECOND;
        long hundredths = remain % HUNDREDTHS_PER_SECOND;
        return String.format("%02d:%02d:%02d.%02d", hours, minutes, secs, hundredths);
    }

    /**
     * 将秒数格式化为mm:ss.SS。
     *
     * @param seconds 秒数
     * @return 圈速文本；秒数为null时返回null
     */
    private String formatBestLapTime(BigDecimal seconds) {
        Long totalHundredths = toHundredths(seconds);
        if (totalHundredths == null) {
            return null;
        }

        long minutes = totalHundredths / (60 * HUNDREDTHS_PER_SECOND);
        long remain = totalHundredths % (60 * HUNDREDTHS_PER_SECOND);
        return String.format("%02d:%02d.%02d", minutes, remain / HUNDREDTHS_PER_SECOND,
                remain % HUNDREDTHS_PER_SECOND);
    }

    /**
     * 将秒数换算为百分秒总数。
     *
     * @param seconds 秒数
     * @return 百分秒总数；秒数为null时返回null
     */
    private Long toHundredths(BigDecimal seconds) {
        return seconds == null ? null
                : seconds.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
