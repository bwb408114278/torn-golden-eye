package pn.torn.goldeneye.torn.service.racing.capture;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.racing.TornRaceTrackEnum;
import pn.torn.goldeneye.repository.dao.racing.TornRacingParticipantDAO;
import pn.torn.goldeneye.repository.dao.racing.TornRacingRaceDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceDetailVO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceResultVO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceScheduleVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * SMTHPC赛事落库逻辑层。
 *
 * <p>主行与明细在同一事务内纯插入，不执行任何删除语句；并发重复由唯一索引兜底，
 * {@code DuplicateKeyException} 由上层捕获，事务回滚后不产生部分写入。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Service
@RequiredArgsConstructor
public class PcRacePersistService {
    private final TornRacingRaceDAO raceDao;
    private final TornRacingParticipantDAO participantDao;
    private final TornUserDAO userDao;
    private final TornSettingFactionManager factionManager;

    /**
     * 在同一事务内写入一场赛事的主行与选手明细。
     *
     * @param race         赛事详情，含全部参赛成绩
     * @param businessDate 赛事业务日期
     */
    @Transactional(rollbackFor = Exception.class)
    public void save(TornRaceDetailVO race, LocalDate businessDate) {
        raceDao.save(buildRace(race, businessDate));
        participantDao.saveBatch(buildParticipants(race));
    }

    /**
     * 构建赛事主行。
     *
     * @param race         赛事详情
     * @param businessDate 赛事业务日期
     * @return 赛事主行
     */
    private TornRacingRaceDO buildRace(TornRaceDetailVO race, LocalDate businessDate) {
        TornRacingRaceDO raceDO = new TornRacingRaceDO();
        raceDO.setRaceId(race.getId());
        raceDO.setTitle(race.getTitle());
        raceDO.setTrackName(TornRaceTrackEnum.titleOf(race.getTrackId()));
        raceDO.setBusinessDate(businessDate);
        TornRaceScheduleVO schedule = race.getSchedule();
        if (schedule != null) {
            raceDO.setStartTime(DateTimeUtils.convertToDateTime(schedule.getStart()));
            raceDO.setEndTime(DateTimeUtils.convertToDateTime(schedule.getEnd()));
        }
        raceDO.setStatus(race.getStatus());
        raceDO.setCapturedTime(LocalDateTime.now());
        return raceDO;
    }

    /**
     * 构建该场全部参赛选手明细，选手归属与昵称取抓取时快照。
     *
     * @param race 赛事详情
     * @return 选手明细
     */
    private List<TornRacingParticipantDO> buildParticipants(TornRaceDetailVO race) {
        List<TornRaceResultVO> resultList = race.getResults();
        List<Long> driverIdList = resultList.stream()
                .map(TornRaceResultVO::getDriverId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(driverIdList);
        Set<Long> allianceFactionIds = factionManager.getIdMap().keySet();

        List<TornRacingParticipantDO> participantList = new ArrayList<>(resultList.size());
        for (TornRaceResultVO result : resultList) {
            participantList.add(buildParticipant(race.getId(), result, userMap, allianceFactionIds));
        }
        return participantList;
    }

    /**
     * 构建单条选手明细。
     *
     * @param raceId             Torn赛事ID
     * @param result             单条成绩
     * @param userMap            参赛选手的本地用户映射
     * @param allianceFactionIds 联盟帮派ID全集
     * @return 选手明细
     */
    private TornRacingParticipantDO buildParticipant(long raceId, TornRaceResultVO result,
                                                     Map<Long, TornUserDO> userMap, Set<Long> allianceFactionIds) {
        TornUserDO user = result.getDriverId() == null ? null : userMap.get(result.getDriverId());
        TornRacingParticipantDO participant = new TornRacingParticipantDO();
        participant.setRaceId(raceId);
        participant.setUserId(result.getDriverId());
        if (user != null) {
            participant.setNickname(user.getNickname());
            participant.setFactionId(user.getFactionId());
        }
        participant.setIsAlliance(user != null && user.getFactionId() != null
                && allianceFactionIds.contains(user.getFactionId()));
        participant.setPosition(result.getPosition());
        participant.setRaceTime(result.getRaceTime());
        participant.setBestLapTime(result.getBestLapTime());
        participant.setHasCrashed(Boolean.TRUE.equals(result.getHasCrashed()));
        return participant;
    }
}
