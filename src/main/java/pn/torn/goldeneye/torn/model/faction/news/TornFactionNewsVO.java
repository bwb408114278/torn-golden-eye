package pn.torn.goldeneye.torn.model.faction.news;

import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

/**
 * 帮派新闻响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Data
public class TornFactionNewsVO {
    /**
     * 新闻ID
     */
    private String id;
    /**
     * 文本
     */
    private String text;
    /**
     * 时间戳
     */
    private Long timestamp;

    public TornFactionItemUsedDO convert2DO(TornItemsManager itemsManager) {
        TornFactionItemUsedDO use = new TornFactionItemUsedDO();
        use.setId(this.id);
        use.setUseTime(DateTimeUtils.convertToDateTime(this.timestamp));

        String[] textArray = text.split(" ", 3);
        use.setUserNickname(textArray[0]);
        use.setUseType(textArray[1]);

        String itemNameText = textArray[2];
        for (String itemName : itemsManager.getSortItemNameList()) {
            if (itemNameText.contains(itemName)) {
                use.setItemName(itemName);
                break;
            }
        }

        return use;
    }
}