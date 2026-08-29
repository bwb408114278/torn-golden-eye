package pn.torn.goldeneye.torn.manager.faction.crime.msg;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendTableBO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * OC消息公共逻辑
 *
 * @author Bai
 * @version 1.5.1
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgManager {
    private final TornFactionOcMsgTableManager msgTableManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final SysSettingDAO settingDao;
    private static final Color RECOMMEND_COLOR = new Color(64, 224, 205);
    private static final Color IDLE_NOT_RECOMMEND_COLOR = new Color(242, 242, 242);

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
        return TableImageUtils.renderTableToBase64(buildRecommendTableData(title, factionId, recommendList));
    }

    /**
     * 构建建议表格数据：每块推荐岗位高亮，空闲且非本块推荐的岗位一律置灰。
     *
     * <p>表格结构为标题1行，每个推荐项一个OC块共3行（分隔行、岗位行、成员行）；OC块内列序即
     * {@code ocMap} 中槽位列表顺序（绘制时已按满员优先、岗位名排序）。同一OC在其他块推荐的岗位
     * 不是本块的推荐结果，在本块同样置灰。</p>
     *
     * @param title         标题
     * @param factionId     帮派ID
     * @param recommendList 推荐列表
     * @return 表格数据
     */
    TableDataBO buildRecommendTableData(String title, long factionId, List<OcRecommendTableBO> recommendList) {
        List<TornFactionOcDO> ocList = ocDao.queryListByIdList(factionId,
                recommendList.stream().map(r -> r.recommend().getOcId()).toList());
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Multimap<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap = LinkedListMultimap.create();
        LinkedList<String> reasonList = new LinkedList<>();
        List<String> blockPositionList = new ArrayList<>();

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
            blockPositionList.add(recommend.getRecommendedPosition().replace(" ", ""));
        }

        // 每块内本块推荐位高亮，其余空闲岗位置灰
        TableDataBO tableData = msgTableManager.buildOcTable(title, ocMap, reasonList);
        highlightOrGraySlots(tableData, ocMap, blockPositionList);
        return tableData;
    }

    /**
     * 每块内本块推荐岗位单元格高亮、其余空闲岗位置灰（岗位行与成员行）；有人岗位保持绿色不动。
     *
     * <p>列定位使用排序后槽位列表下标（列=下标+1），与绘表时的列序一致，
     * 不依赖岗位单元格文本匹配。</p>
     *
     * @param tableData        表格数据
     * @param ocMap            OC与槽位列表映射，槽位列表顺序与表格列序一致
     * @param blockPositionList 与ocMap块序一致的本块推荐岗位名列表（去空格）
     */
    private void highlightOrGraySlots(TableDataBO tableData,
                                      Multimap<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap,
                                      List<String> blockPositionList) {
        TableImageUtils.TableConfig config = tableData.getTableConfig();
        int blockIndex = 0;
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entries()) {
            String recommendedPosition = blockPositionList.get(blockIndex);
            // 标题一行，跳过每个OC块的分隔行后为岗位行，成员行为其下一行
            int positionRowIndex = 2 + blockIndex * 3;
            List<TornFactionOcSlotDO> slotList = entry.getValue();
            for (int i = 0; i < slotList.size(); i++) {
                TornFactionOcSlotDO slot = slotList.get(i);
                if (slot.getUserId() != null) {
                    continue;
                }

                boolean recommended = recommendedPosition.equals(slot.getPosition().replace(" ", ""));
                Color slotColor = recommended ? RECOMMEND_COLOR : IDLE_NOT_RECOMMEND_COLOR;
                config.setCellStyle(positionRowIndex, i + 1, buildSlotCellStyle(slotColor));
                config.setCellStyle(positionRowIndex + 1, i + 1, buildSlotCellStyle(slotColor));
            }
            blockIndex++;
        }
    }

    /**
     * 构建空闲岗位单元格样式。
     *
     * @param bgColor 背景色
     * @return 单元格样式
     */
    private TableImageUtils.CellStyle buildSlotCellStyle(Color bgColor) {
        return new TableImageUtils.CellStyle()
                .setAlignment(TableImageUtils.TextAlignment.LEFT)
                .setBgColor(bgColor);
    }
}