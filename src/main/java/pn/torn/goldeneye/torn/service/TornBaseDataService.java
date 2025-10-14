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
import pn.torn.goldeneye.repository.dao.torn.TornItemsDAO;
import pn.torn.goldeneye.repository.dao.torn.TornStocksDAO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.manager.torn.TornStocksManager;
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
 * @version 0.3.0
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
    private final TornItemsDAO itemsDao;
    private final TornStocksDAO stocksDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    @PostConstruct
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_BASE_DATA_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 35, 0);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            virtualThreadExecutor.execute(this::spiderBaseData);
        } else {
            addScheduleTask(from.plusDays(1));
        }
    }

    /**
     * 爬取帮派成员
     */
    public void spiderBaseData() {
        try {
            spiderBankRate();
            spiderPointValue();
            spiderItems();
            spiderStocks();

            LocalDate to = LocalDate.now();
            settingDao.updateSetting(SettingConstants.KEY_BASE_DATA_LOAD, DateTimeUtils.convertToString(to));
            addScheduleTask(to.plusDays(1).atTime(8, 35, 0));
        } catch (Exception e) {
            // 失败5分钟后重试
            addScheduleTask(LocalDateTime.now().plusMinutes(5));
        }
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
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime execTime) {
        taskService.updateTask("base-date-reload", this::spiderBaseData, execTime);
    }
}