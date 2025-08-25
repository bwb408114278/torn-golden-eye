package pn.torn.goldeneye.torn.service.faction.armory;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionNewsTypeEnum;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsDTO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
@Order(10002)
public class ItemUsedService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornFactionItemUsedDAO usedDao;
    private final TornUserDAO userDao;
    private final SysSettingDAO settingDao;

    @PostConstruct
    public void init() {
        String value = settingDao.querySettingValue(TornConstants.SETTING_KEY_ITEM_USE_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            virtualThreadExecutor.execute(() -> spiderItemUseData(from, to));
        }

        addScheduleTask(to);
    }

    /**
     * 爬取物品使用记录
     */
    public void spiderItemUseData(LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionNewsDTO param;
        LocalDateTime queryTo = to;
        while (true) {
            param = new TornFactionNewsDTO(TornFactionNewsTypeEnum.ARMORY_ACTION, from, queryTo, limit);
            TornFactionNewsListVO resp = tornApi.sendRequest(param, TornFactionNewsListVO.class);
            if (resp == null) {
                continue;
            }

            List<TornFactionItemUsedDO> newsList = resp.getNews().stream().map(TornFactionNewsVO::convert2DO).toList();
            List<TornFactionItemUsedDO> dataList = buildDataList(newsList);
            if (!CollectionUtils.isEmpty(dataList)) {
                Set<String> nicknameSet = dataList.stream().map(TornFactionItemUsedDO::getUserNickname).collect(Collectors.toSet());
                Map<String, Long> nicknameMap = userDao.queryNicknameMap(nicknameSet);
                dataList.forEach(n -> n.setUserId(nicknameMap.get(n.getUserNickname())));
                usedDao.saveBatch(dataList);
            }

            if (newsList.size() < limit) {
                break;
            } else {
                queryTo = DateTimeUtils.convertToDateTime(resp.getNews().get(limit - 1).getTimestamp());
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BizException("同步帮派记录的等待时间出错", e);
                }
            }
        }

        settingDao.updateSetting(TornConstants.SETTING_KEY_ITEM_USE_LOAD, DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("item-use-reload",
                () -> spiderItemUseData(to.plusSeconds(1), to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(10L));
    }

    /**
     * 构建可以插入的数据列表
     */
    private List<TornFactionItemUsedDO> buildDataList(List<TornFactionItemUsedDO> newsList) {
        if (CollectionUtils.isEmpty(newsList)) {
            return List.of();
        }

        List<String> idList = newsList.stream().map(TornFactionItemUsedDO::getId).toList();
        List<TornFactionItemUsedDO> oldDataList = usedDao.lambdaQuery().in(TornFactionItemUsedDO::getId, idList).list();
        List<String> oldIdList = oldDataList.stream().map(TornFactionItemUsedDO::getId).toList();

        List<TornFactionItemUsedDO> resultList = new ArrayList<>();
        for (TornFactionItemUsedDO news : newsList) {
            boolean notValidType = !news.getUseType().equals("used") && !news.getUseType().equals("filled");
            boolean isOldData = oldIdList.contains(news.getId());
            boolean isRefill = news.getItemName().contains("refill");
            if (notValidType || isOldData || isRefill) {
                continue;
            }

            resultList.add(news);
        }

        return resultList;
    }
}