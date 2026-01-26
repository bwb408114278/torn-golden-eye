package pn.torn.goldeneye.torn.manager.torn;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.torn.TornItemHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.TornItemHistoryDO;
import pn.torn.goldeneye.torn.model.torn.items.TornItemsListVO;
import pn.torn.goldeneye.torn.model.torn.items.TornItemsVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 物品历史记录公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.26
 */
@Component
@RequiredArgsConstructor
public class TornItemHistoryManager {
    private final TornItemHistoryDAO itemHistoryDao;

    /**
     * 保存物品历史
     */
    public void saveItemHistory(TornItemsListVO resp) {
        List<TornItemHistoryDO> historyList = resp.getItems().stream()
                .map(this::convert2DO)
                .toList();
        itemHistoryDao.saveBatch(historyList);
    }

    /**
     * 转换数据映射类
     */
    private TornItemHistoryDO convert2DO(TornItemsVO item) {
        TornItemHistoryDO history = new TornItemHistoryDO();
        history.setItemId(item.getId());
        history.setItemName(item.getName());
        history.setMarketPrice(item.getValue().getMarketPrice());
        history.setCirculation(item.getCirculation());
        history.setRegDate(LocalDate.now());
        return history;
    }
}