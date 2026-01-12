package pn.torn.goldeneye.torn.manager.faction.armory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.torn.enums.TornFactionNewsTypeEnum;
import pn.torn.goldeneye.repository.dao.faction.funds.TornFactionGiveFundsDAO;
import pn.torn.goldeneye.repository.model.faction.funds.TornFactionGiveFundsDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsDTO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帮派取钱记录公共逻辑类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FactionGiveFundsManager {
    private final TornApi tornApi;
    private final TornFactionGiveFundsDAO giveFundsDao;
    private static final Pattern PATTERN = Pattern.compile(
            "<a href = \"http://www\\.torn\\.com/profiles\\.php\\?XID=(\\d+)\">([^<]+)</a> was given \\$([\\d,]+) by <a href = \"http://www\\.torn\\.com/profiles\\.php\\?XID=(\\d+)\">([^<]+)</a>");

    /**
     * 爬取取款记录
     */
    public void spiderGiveFundsData(TornSettingFactionDO faction, LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionNewsDTO param;
        TornFactionNewsListVO resp;
        LocalDateTime queryTo = to;
        List<TornFactionGiveFundsDO> newsList;

        do {
            param = new TornFactionNewsDTO(TornFactionNewsTypeEnum.GIVE_FUNDS, from, queryTo, limit);
            resp = tornApi.sendRequest(faction.getId(), param, TornFactionNewsListVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getNews())) {
                break;
            }

            newsList = resp.getNews().stream().map(n -> convert2DO(faction.getId(), n)).toList();
            List<TornFactionGiveFundsDO> dataList = buildDataList(newsList);
            if (!CollectionUtils.isEmpty(dataList)) {
                giveFundsDao.saveBatch(dataList);
            }

            queryTo = DateTimeUtils.convertToDateTime(resp.getNews().getLast().getTimestamp());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (resp.getNews().size() >= limit);
    }

    /**
     * 解析HTML文本，提取取钱信息
     */
    private TornFactionGiveFundsDO convert2DO(long factionId, TornFactionNewsVO news) {
        TornFactionGiveFundsDO funds = new TornFactionGiveFundsDO();
        funds.setId(news.getId());
        funds.setFactionId(factionId);
        funds.setWithdrawTime(DateTimeUtils.convertToDateTime(news.getTimestamp()));

        Matcher matcher = PATTERN.matcher(news.getText());
        if (matcher.find()) {
            funds.setUserId(Long.parseLong(matcher.group(1)));
            funds.setHandleUserId(Long.parseLong(matcher.group(4)));

            String amountStr = matcher.group(3).replace(",", "");
            funds.setAmount(Long.parseLong(amountStr));
        }

        return funds;
    }

    /**
     * 构建可以插入的数据列表
     */
    private List<TornFactionGiveFundsDO> buildDataList(List<TornFactionGiveFundsDO> newsList) {
        if (CollectionUtils.isEmpty(newsList)) {
            return List.of();
        }

        List<String> idList = newsList.stream().map(TornFactionGiveFundsDO::getId).toList();
        List<TornFactionGiveFundsDO> oldDataList = giveFundsDao.lambdaQuery()
                .in(TornFactionGiveFundsDO::getId, idList)
                .list();
        List<String> oldIdList = oldDataList.stream().map(TornFactionGiveFundsDO::getId).toList();

        List<TornFactionGiveFundsDO> resultList = new ArrayList<>();
        for (TornFactionGiveFundsDO news : newsList) {
            if (oldIdList.contains(news.getId())) {
                continue;
            }

            resultList.add(news);
        }

        return resultList;
    }
}