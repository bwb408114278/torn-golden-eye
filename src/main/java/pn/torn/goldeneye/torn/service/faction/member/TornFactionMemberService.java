package pn.torn.goldeneye.torn.service.faction.member;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Torn帮派成员逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.10
 */
@Service
@RequiredArgsConstructor
@Order(10006)
public class TornFactionMemberService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornUserDAO userDao;
    private final SysSettingDAO settingDao;
    private final TornSettingFactionDAO settingFactionDao;
    private final ProjectProperty projectProperty;

    @PostConstruct
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_FACTION_MEMBER_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            virtualThreadExecutor.execute(() -> spiderFactionMember(to));
        }

        addScheduleTask(to);
    }

    /**
     * 爬取帮派成员
     */
    public void spiderFactionMember(LocalDateTime to) {
        List<TornSettingFactionDO> factionList = settingFactionDao.list();
        List<TornUserDO> newUserList = new ArrayList<>();
        List<Long> allUserIdList = new ArrayList<>();

        for (TornSettingFactionDO faction : factionList) {
            TornFactionMemberDTO param = new TornFactionMemberDTO(faction.getId());
            TornFactionMemberListVO memberList = tornApi.sendRequest(param, TornFactionMemberListVO.class);

            List<Long> userIdList = memberList.getMembers().stream().map(TornFactionMemberVO::getId).toList();
            allUserIdList.addAll(userIdList);
            List<TornUserDO> userList = userDao.lambdaQuery().in(TornUserDO::getId, userIdList).list();

            for (TornFactionMemberVO member : memberList.getMembers()) {
                TornUserDO oldData = userList.stream().filter(u -> u.getId().equals(member.getId())).findAny().orElse(null);
                TornUserDO newData = member.convert2DO(faction.getId());

                if (oldData == null) {
                    newUserList.add(newData);
                } else if (!oldData.equals(newData)) {
                    userDao.updateById(newData);
                }
            }
        }

        if (!CollectionUtils.isEmpty(newUserList)) {
            userDao.saveBatch(newUserList);
        }
        removeFactionMember(allUserIdList);

        settingDao.updateSetting(SettingConstants.KEY_FACTION_MEMBER_LOAD,
                DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 移除不在SMTH的成员
     */
    private void removeFactionMember(List<Long> allUserIdList) {
        List<TornUserDO> allFactionUserList = userDao.list();
        for (TornUserDO user : allFactionUserList) {
            if (!allUserIdList.contains(user.getId())) {
                userDao.lambdaUpdate().set(TornUserDO::getFactionId, 0L).eq(TornUserDO::getId, user.getId()).update();
            }
        }
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("faction-member-reload",
                () -> spiderFactionMember(to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(3L));
    }
}