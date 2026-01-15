package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAuctionDAO;
import pn.torn.goldeneye.repository.model.torn.TornAuctionDO;
import pn.torn.goldeneye.torn.manager.torn.TornAuctionManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.torn.auction.TornAuctionDTO;
import pn.torn.goldeneye.torn.model.torn.auction.TornAuctionListVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 拍卖行逻辑类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_AUCTION)
@Slf4j
public class TornAuctionService {
    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornAuctionManager auctionManager;
    private final TornItemsManager itemsManager;
    private final TornAuctionDAO auctionDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String value = settingDao.querySettingValue(SettingConstants.KEY_AUCTION_LOAD);
        LocalDateTime from = DateTimeUtils.convertToDate(value).atTime(8, 0, 0);
        LocalDateTime to = LocalDate.now().atTime(7, 59, 59);

        if (LocalDateTime.now().minusDays(1).isAfter(from)) {
            spiderAuctionData(from, to, true);
        }

        addScheduleTask(to);
    }

    /**
     * 爬取拍卖记录
     */
    public void spiderAuctionData(LocalDateTime from, LocalDateTime to, boolean refreshTask) {
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        futureList.add(CompletableFuture.runAsync(() -> handleSpiderTask(from, to), virtualThreadExecutor));
        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();

        if (refreshTask) {
            settingDao.updateSetting(SettingConstants.KEY_AUCTION_LOAD, DateTimeUtils.convertToString(to.toLocalDate()));
            addScheduleTask(to);
        }
    }

    /**
     * 爬取物品使用记录
     */
    public void handleSpiderTask(LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornAuctionDTO param;
        TornAuctionListVO resp;
        LocalDateTime queryFrom = from;
        List<TornAuctionDO> auctionList;

        do {
            param = new TornAuctionDTO(queryFrom, to, limit);
            resp = tornApi.sendRequest(param, TornAuctionListVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getAuctionList())) {
                break;
            }

            auctionList = resp.getAuctionList().stream().map(a -> a.convert2DO(itemsManager)).toList();
            List<TornAuctionDO> dataList = buildDataList(auctionList);
            if (!CollectionUtils.isEmpty(dataList)) {
                auctionDao.saveBatch(dataList);
                auctionManager.uploadAuction(dataList);
            }

            queryFrom = DateTimeUtils.convertToDateTime(resp.getAuctionList().getLast().getTimestamp());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (resp.getAuctionList().size() >= limit);
    }

    /**
     * 构建可以插入的数据列表
     */
    private List<TornAuctionDO> buildDataList(List<TornAuctionDO> auctionList) {
        if (CollectionUtils.isEmpty(auctionList)) {
            return List.of();
        }

        List<Long> idList = auctionList.stream().map(TornAuctionDO::getId).toList();
        List<TornAuctionDO> oldDataList = auctionDao.lambdaQuery().in(TornAuctionDO::getId, idList).list();
        List<Long> oldIdList = oldDataList.stream().map(TornAuctionDO::getId).toList();

        List<TornAuctionDO> resultList = new ArrayList<>();
        for (TornAuctionDO auction : auctionList) {
            boolean isNotRwEquip = "None".equals(auction.getItemRarity());
            boolean isOldData = oldIdList.contains(auction.getId());
            if (isNotRwEquip || isOldData) {
                continue;
            }

            resultList.add(auction);
        }

        return resultList;
    }

    /**
     * 添加定时任务
     */
    private void addScheduleTask(LocalDateTime to) {
        taskService.updateTask("auction-reload",
                () -> spiderAuctionData(to.plusSeconds(1), to.plusDays(1), true),
                to.plusDays(1).plusSeconds(1).plusMinutes(20));
    }
}