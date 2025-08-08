package pn.torn.goldeneye.msg.strategy.faction.news;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionNewsTypeEnum;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsDTO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsVO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;

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
public class ItemUsedSyncStrategyImpl extends BaseMsgStrategy {
    private final TornApi tornApi;
    private final TornFactionItemUsedDAO usedDao;
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return BotCommands.ITEM_USED;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 2 || !NumberUtils.isInt(msgArray[0]) || !NumberUtils.isInt(msgArray[1])) {
            return super.sendErrorFormatMsg();
        }

        LocalDateTime from = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[0]));
        LocalDateTime to = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[1]));
        if (from.isAfter(to)) {
            return super.sendErrorFormatMsg();
        }

//        int limit = 100;
//        TornFactionNewsDTO param;
//        while (true) {
//            param = new TornFactionNewsDTO(TornFactionNewsTypeEnum.ARMORY_ACTION, from, to, limit);
//            TornFactionNewsListVO resp = tornApi.sendRequest(param, TornFactionNewsListVO.class);
//            if (resp == null) {
//                continue;
//            }
//
//            List<TornFactionItemUsedDO> newsList = resp.getNews().stream().map(TornFactionNewsVO::convert2DO).toList();
//            List<TornFactionItemUsedDO> dataList = buildDataList(newsList);
//            if (!CollectionUtils.isEmpty(dataList)) {
//                Set<String> nicknameSet = dataList.stream().map(TornFactionItemUsedDO::getUserNickname).collect(Collectors.toSet());
//                Map<String, Long> nicknameMap = userDao.getNicknameMap(nicknameSet);
//                dataList.forEach(n -> n.setUserId(nicknameMap.get(n.getUserNickname())));
//                usedDao.saveBatch(dataList);
//            }
//
//            if (newsList.size() < limit) {
//                break;
//            } else {
//                to = DateTimeUtils.convertToDateTime(resp.getNews().get(limit - 1).getTimestamp());
//                try {
//                    Thread.sleep(1000L);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                    throw new BizException("同步帮派记录的等待时间出错", e);
//                }
//            }
//        }

        return super.buildTextMsg("同步" +
                DateTimeUtils.convertToString(from) +
                "到" +
                DateTimeUtils.convertToString(DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[1]))) +
                "的帮派物品使用记录完成");
    }

    /**
     * 构建可以插入的数据列表
     */
    private List<TornFactionItemUsedDO> buildDataList(List<TornFactionItemUsedDO> newsList) {
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