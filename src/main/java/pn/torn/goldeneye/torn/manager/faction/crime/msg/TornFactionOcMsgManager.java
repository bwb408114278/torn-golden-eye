package pn.torn.goldeneye.torn.manager.faction.crime.msg;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendTableBO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * OC消息公共逻辑
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgManager {
    private final Bot bot;
    private final TornUserManager userManager;
    private final TornFactionOcMsgTableManager msgTableManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;
    private final SysSettingDAO settingDao;
    private static final Color RECOMMEND_COLOR = new Color(64, 224, 205);

    /**
     * 构建OC表格
     *
     * @param title 标题
     * @return 表格图片的Base64
     */
    public String buildOcTable(String title, List<TornFactionOcDO> ocList) {
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Map<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap = LinkedHashMap.newLinkedHashMap(ocList.size());
        for (TornFactionOcDO oc : ocList) {
            List<TornFactionOcSlotDO> currentSlotList = new ArrayList<>(slotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId())).toList());
            ocMap.put(oc, currentSlotList);
        }

        Multimap<TornFactionOcDO, List<TornFactionOcSlotDO>> multiMap = LinkedListMultimap.create();
        LinkedList<String> splitLine = new LinkedList<>();
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entrySet()) {
            multiMap.put(entry.getKey(), entry.getValue());
            splitLine.add(entry.getKey().getName());
        }
        TableDataBO table = msgTableManager.buildOcTable(title, multiMap, splitLine);

        String lastRefreshTime = settingDao.querySettingValue(SettingConstants.KEY_OC_LOAD);
        table.getTableData().add(List.of("上次更新时间: " + lastRefreshTime,
                "", "", "", "", ""));

        int row = ocList.size() * 3 + 1;
        table.getTableConfig().addMerge(row, 0, 1, 7)
                .setCellStyle(row, 0, new TableImageUtils.CellStyle()
                        .setFont(new Font("微软雅黑", Font.BOLD, 14))
                        .setAlignment(TableImageUtils.TextAlignment.LEFT));
        return TableImageUtils.renderTableToBase64(table);
    }

    /**
     * 构建建议表格
     */
    public String buildRecommendTable(String title, long factionId, Map<TornUserDO, OcRecommendationVO> map) {
        return buildRecommendTable(title, factionId, map.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry ->
                        new OcRecommendTableBO(entry.getKey(), entry.getValue())).toList());
    }

    /**
     * 构建建议表格
     */
    public String buildRecommendTable(String title, long factionId, List<OcRecommendTableBO> recommendList) {
        List<TornFactionOcDO> ocList = ocDao.queryListByIdList(factionId,
                recommendList.stream().map(r -> r.recommend().getOcId()).toList());
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Multimap<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap = LinkedListMultimap.create();
        LinkedList<String> reasonList = new LinkedList<>();

        for (OcRecommendTableBO entry : recommendList) {
            OcRecommendationVO recommend = entry.recommend();
            TornFactionOcDO oc = ocList.stream()
                    .filter(o -> o.getId().equals(recommend.getOcId()))
                    .findAny().orElse(null);
            if (oc == null) {
                continue;
            }

            List<TornFactionOcSlotDO> currentSlotList = new ArrayList<>(slotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId())).toList());
            ocMap.put(oc, currentSlotList);
            reasonList.offer(entry.buildReasonText());
        }

        // 高亮推荐岗位
        TableDataBO tableData = msgTableManager.buildOcTable(title, ocMap, reasonList);
        TableImageUtils.TableConfig config = tableData.getTableConfig();
        for (int i = 0; i < recommendList.size(); i++) {
            // 标题一行，加上每个分隔行
            int startIndex = 1 + i * 2 + 1;
            List<String> positionRow = tableData.getTableData().get(i + startIndex);
            int column = positionRow.indexOf(recommendList.get(i).recommend()
                    .getRecommendedPosition().replace(" ", ""));
            if (column < 0) {
                continue;
            }

            config.setCellStyle(i + startIndex, column, new TableImageUtils.CellStyle()
                    .setAlignment(TableImageUtils.TextAlignment.LEFT)
                    .setBgColor(RECOMMEND_COLOR));
            config.setCellStyle(i + startIndex + 1, column, new TableImageUtils.CellStyle()
                    .setAlignment(TableImageUtils.TextAlignment.LEFT)
                    .setBgColor(RECOMMEND_COLOR));
        }

        return TableImageUtils.renderTableToBase64(tableData);
    }

    /**
     * 构建At消息
     *
     * @param userIdList 用户ID列表
     */
    public List<QqMsgParam<?>> buildAtMsg(Collection<Long> userIdList) {
        Map<Long, TornUserDO> userMap = userManager.getUserMap();
        List<QqMsgParam<?>> resultList = new ArrayList<>();

        for (long userId : userIdList) {
            TornUserDO user = userMap.get(userId);
            if (user == null || user.getQqId().equals(0L)) {
                resultList.add(new TextQqMsg((user == null ?
                        userId :
                        user.getNickname()) + "[" + userId + "] "));
            } else {
                resultList.add(new AtQqMsg(user.getQqId()));
            }
        }
        return resultList;
    }
}