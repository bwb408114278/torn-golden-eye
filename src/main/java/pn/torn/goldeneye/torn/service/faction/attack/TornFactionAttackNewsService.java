package pn.torn.goldeneye.torn.service.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.torn.enums.TornFactionNewsTypeEnum;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionAttackNewsDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackNewsDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsDTO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsVO;
import pn.torn.goldeneye.torn.service.data.TornAttackLogService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帮派攻击新闻逻辑类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornFactionAttackNewsService {
    private final TornApi tornApi;
    private final TornAttackLogService attackLogService;
    private final TornFactionAttackNewsDAO attackNewsDao;
    private static final Pattern USER_ID_PATTERN = Pattern.compile("XID=(\\d+)");
    private static final Pattern RESPECT_PATTERN = Pattern.compile("\\(([+-]?\\d+\\.\\d+)\\)");
    private static final Pattern ATTACK_LOG_ID_PATTERN = Pattern.compile("attackLog&ID=([a-f0-9]+)");

    /**
     * 爬取攻击记录
     */
    public void spiderAttackData(TornSettingFactionDO faction, LocalDateTime from, LocalDateTime to,
                                 Map<Long, String> userNameMap) {
        int limit = 100;
        TornFactionNewsDTO param;
        LocalDateTime queryTo = to;
        TornFactionNewsListVO resp;
        List<TornFactionAttackNewsDO> newsList;

        do {
            param = new TornFactionNewsDTO(TornFactionNewsTypeEnum.ATTACK, from, queryTo, limit);
            resp = tornApi.sendRequest(faction.getId(), param, TornFactionNewsListVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getNews())) {
                break;
            }

            newsList = parseNewsList(faction.getId(), resp);
            if (!CollectionUtils.isEmpty(newsList)) {
                attackNewsDao.saveBatch(newsList);
                List<String> logIdList = newsList.stream().map(TornFactionAttackNewsDO::getAttackLogId).toList();
                attackLogService.saveAttackLog(faction.getId(), logIdList, userNameMap);
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
     * 解析新闻列表为攻击记录
     */
    public List<TornFactionAttackNewsDO> parseNewsList(long factionId, TornFactionNewsListVO resp) {
        if (resp == null || resp.getNews() == null) {
            return new ArrayList<>();
        }

        List<String> idList = resp.getNews().stream().map(TornFactionNewsVO::getId).toList();
        List<TornFactionAttackNewsDO> recordList = attackNewsDao.lambdaQuery().in(TornFactionAttackNewsDO::getId, idList).list();

        List<TornFactionAttackNewsDO> attackList = new ArrayList<>();
        for (TornFactionNewsVO news : resp.getNews()) {
            try {
                TornFactionAttackNewsDO attack = parseNews(factionId, news);
                boolean isExists = recordList.stream()
                        .anyMatch(r -> r.getId().equals(news.getId()));
                if (attack != null && !isExists) {
                    attackList.add(attack);
                }
            } catch (Exception e) {
                log.error("解析新闻失败, id: {}, text: {}", news.getId(), news.getText(), e);
            }
        }

        return attackList;
    }

    /**
     * 解析单条新闻为攻击记录
     */
    public TornFactionAttackNewsDO parseNews(long factionId, TornFactionNewsVO news) {
        if (news == null || news.getText() == null) {
            return null;
        }

        TornFactionAttackNewsDO attack = new TornFactionAttackNewsDO();
        attack.setId(news.getId());
        attack.setFactionId(factionId);
        attack.setAttackTime(DateTimeUtils.convertToDateTime(news.getTimestamp()));

        // 解析HTML文本
        Document doc = Jsoup.parse(news.getText());
        Elements links = doc.select("a[href*=profiles.php]");
        String plainText = doc.text();

        // 判断是否为匿名攻击
        boolean isAnonymous = news.getText().startsWith("Someone");
        int defendIndex = 0;
        if (isAnonymous) {
            attack.setAttackUserId(0L);
            attack.setAttackUserNickname("Someone");
        } else {
            Element attackLink = links.getFirst();
            attack.setAttackUserId(extractUserId(attackLink.attr("href")));
            attack.setAttackUserNickname(attackLink.text());
            defendIndex = 1;
        }

        Element defendLink = links.get(defendIndex);
        attack.setDefendUserId(extractUserId(defendLink.attr("href")));
        attack.setDefendUserNickname(defendLink.text());

        attack.setAttackResult(parseAttackResult(plainText));
        attack.setRespectChange(extractRespectChange(plainText));
        attack.setAttackLogId(extractAttackLogId(news.getText()));

        return attack;
    }

    /**
     * 从URL中提取用户ID
     */
    private long extractUserId(String href) {
        Matcher matcher = USER_ID_PATTERN.matcher(href);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }

        return 0L;
    }

    /**
     * 解析攻击结果
     */
    private String parseAttackResult(String plainText) {
        if (plainText.contains("attacked")) {
            return "leave";
        } else if (plainText.contains("mugged")) {
            return "mug";
        } else if (plainText.contains("hospitalized")) {
            return "hospital";
        } else if (plainText.contains("but lost")) {
            return "lost";
        } else if (plainText.contains("and escaped")) {
            return "escape";
        } else if (plainText.contains("and stalemated")) {
            return "stalemate";
        }

        return plainText;
    }

    /**
     * 提取面子变化
     */
    private BigDecimal extractRespectChange(String plainText) {
        Matcher matcher = RESPECT_PATTERN.matcher(plainText);
        if (matcher.find()) {
            try {
                String respectStr = matcher.group(1);
                return new BigDecimal(respectStr);
            } catch (NumberFormatException e) {
                log.warn("解析面子失败: {}", plainText, e);
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 提供攻击日志ID
     */
    private String extractAttackLogId(String htmlText) {
        Matcher matcher = ATTACK_LOG_ID_PATTERN.matcher(htmlText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}