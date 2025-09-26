package pn.torn.goldeneye.torn.service;

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
import pn.torn.goldeneye.repository.dao.torn.TornItemsDAO;
import pn.torn.goldeneye.repository.dao.torn.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.manager.torn.TornStocksManager;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankDTO;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankVO;
import pn.torn.goldeneye.torn.model.torn.items.TornItemsDTO;
import pn.torn.goldeneye.torn.model.torn.items.TornItemsListVO;
import pn.torn.goldeneye.torn.model.torn.items.TornItemsVO;
import pn.torn.goldeneye.torn.model.torn.stats.TornStatsDTO;
import pn.torn.goldeneye.torn.model.torn.stats.TornStatsVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDTO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Torn基础数据逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.10
 */
@Service
@RequiredArgsConstructor
@Order(10006)
public class TornBaseDataService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final SysSettingManager settingManager;
    private final TornStocksManager stocksManager;
    private final TornItemsManager itemsManager;
    private final TornUserDAO userDao;
    private final TornItemsDAO itemsDao;
    private final TornStocksDAO stocksDao;
    private final SysSettingDAO settingDao;
    private final TornSettingFactionDAO settingFactionDao;
    private final ProjectProperty projectProperty;

    @PostConstruct
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_BASE_DATA_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            virtualThreadExecutor.execute(() -> spiderBaseData(to));
        }

        addScheduleTask(to);
    }

    /**
     * 爬取帮派成员
     */
    public void spiderBaseData(LocalDateTime to) {
        spiderFactionMember();
        spiderBankRate();
        spiderPointValue();
        spiderItems();
        spiderStocks();

        settingDao.updateSetting(SettingConstants.KEY_BASE_DATA_LOAD,
                DateTimeUtils.convertToString(to.toLocalDate()));
        addScheduleTask(to);
    }

    /**
     * 爬取帮派成员
     */
    public void spiderFactionMember() {
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
    }

    /**
     * 爬取银行利率
     */
    public void spiderBankRate() {
        TornBankVO bank = tornApi.sendRequest(new TornBankDTO(), TornBankVO.class);
        BigDecimal bankRate = bank.getBank().get("3m");
        settingManager.updateSetting(SettingConstants.KEY_BANK_RATE, bankRate.toString());
    }

    /**
     * 爬取PT价值
     */
    public void spiderPointValue() {
        TornStatsVO stats = tornApi.sendRequest(new TornStatsDTO(), TornStatsVO.class);
        Long pointValue = stats.getStats().get("points_averagecost");
        settingManager.updateSetting(SettingConstants.KEY_POINT_VALUE, pointValue.toString());
    }

    /**
     * 爬取物品
     */
    public void spiderItems() {
        TornItemsListVO items = tornApi.sendRequest(new TornItemsDTO(), TornItemsListVO.class);
        List<TornItemsDO> itemList = items.getItems().stream().map(TornItemsVO::convert2DO).toList();
        List<TornItemsDO> oldDataList = itemsDao.list();

        List<TornItemsDO> newDataList = new ArrayList<>();
        List<TornItemsDO> upadteDataList = new ArrayList<>();
        for (TornItemsDO item : itemList) {
            if (oldDataList.stream().anyMatch(i -> i.getId().equals(item.getId()))) {
                upadteDataList.add(item);
            } else {
                newDataList.add(item);
            }
        }

        if (!CollectionUtils.isEmpty(newDataList)) {
            itemsDao.saveBatch(newDataList);
        }

        if (!CollectionUtils.isEmpty(upadteDataList)) {
            itemsDao.updateBatchById(upadteDataList);
        }
        itemsManager.refreshCache();
    }

    /**
     * 爬取物品
     */
    public void spiderStocks() {
        TornStocksVO stocksResp = tornApi.sendRequest(new TornStocksDTO(), TornStocksVO.class);
        List<TornStocksDO> stocksList = stocksResp.getStocks().values().stream().map(stocksManager::convert2DO).toList();
        List<TornStocksDO> oldDataList = stocksDao.list();

        List<TornStocksDO> newDataList = new ArrayList<>();
        List<TornStocksDO> upadteDataList = new ArrayList<>();
        for (TornStocksDO stocks : stocksList) {
            if (oldDataList.stream().anyMatch(i -> i.getId().equals(stocks.getId()))) {
                upadteDataList.add(stocks);
            } else {
                newDataList.add(stocks);
            }
        }

        if (!CollectionUtils.isEmpty(newDataList)) {
            stocksDao.saveBatch(newDataList);
        }

        if (!CollectionUtils.isEmpty(upadteDataList)) {
            stocksDao.updateBatchById(upadteDataList);
        }
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
                () -> spiderBaseData(to.plusDays(1)),
                to.plusDays(1).plusSeconds(1).plusMinutes(3L));
    }
}