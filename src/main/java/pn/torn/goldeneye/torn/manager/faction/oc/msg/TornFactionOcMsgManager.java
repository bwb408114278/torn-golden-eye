package pn.torn.goldeneye.torn.manager.faction.oc.msg;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OC消息公共逻辑
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgManager {
    private final TornFactionOcMsgTableManager msgTableManager;
    private final TornFactionOcSlotDAO slotDao;
    private final SysSettingDAO settingDao;

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

        TableDataBO table = msgTableManager.buildOcTable(title, ocMap);

        String lastRefreshTime = settingDao.querySettingValue(SettingConstants.KEY_OC_LOAD);
        table.getTableData().add(List.of("上次更新时间: " + lastRefreshTime,
                "", "", "", "", ""));

        int row = ocList.size() * 3;
        table.getTableConfig().addMerge(row, 0, 1, 7)
                .setCellStyle(row, 0, new TableImageUtils.CellStyle()
                        .setFont(new Font("微软雅黑", Font.BOLD, 14))
                        .setAlignment(TableImageUtils.TextAlignment.LEFT));
        return TableImageUtils.renderTableToBase64(table);
    }
}