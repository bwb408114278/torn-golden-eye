package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式候选落库顺序契约测试,锁定SignalEvent与VirtualBatch的非空关联保存顺序。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@DisplayName("正式候选落库顺序契约测试")
class StockFormalCandidatePersistenceOrderTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockBuySignalEvaluator.java");

    @Test
    @DisplayName("正式候选保存_事件先保存批次携带事件ID最后回写并预留槽位")
    void formalCandidatePersistence_savesEventBeforeBatchAndReservesSlotLast() throws Exception {
        String source = Files.readString(SOURCE_PATH, StandardCharsets.UTF_8);

        int eventIndex = source.indexOf("recordFormalSignalEvent(");
        int signalEventIdIndex = source.indexOf("batch.setSignalEventId(event.getId())", eventIndex);
        int batchSaveIndex = source.indexOf("virtualBatchDao.save(batch)", signalEventIdIndex);
        int formalBatchIdIndex = source.indexOf("event.setFormalBatchId(batch.getId())", batchSaveIndex);
        int eventUpdateIndex = source.indexOf("updateSignalEventBatchIds(event)", formalBatchIdIndex);
        int reserveSlotIndex = source.indexOf("portfolioService.reserveSlot(slot, reservedAmount, batch.getId())",
                eventUpdateIndex);

        assertTrue(eventIndex >= 0, "必须先调用正式SignalEvent保存入口");
        assertTrue(signalEventIdIndex > eventIndex, "VirtualBatch必须先设置signalEventId");
        assertTrue(batchSaveIndex > signalEventIdIndex, "VirtualBatch保存前必须已具备signalEventId");
        assertTrue(formalBatchIdIndex > batchSaveIndex, "批次保存后才能取得formalBatchId");
        assertTrue(eventUpdateIndex > formalBatchIdIndex, "必须回写SignalEvent的formalBatchId");
        assertTrue(reserveSlotIndex > eventUpdateIndex, "事件关联完成后才能预留正式槽位");
    }
}
