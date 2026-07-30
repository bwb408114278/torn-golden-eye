package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockObservationResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拒绝观察结果字段与结果编码契约测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("拒绝观察结果字段契约测试")
class StockSignalEventObservationResultContractTest {

    @Test
    @DisplayName("结果枚举_包含三类可审计观察结果编码")
    void observationResultEnum_containsRequiredCodes() {
        assertEquals("NO_THEORETICAL_ENTRY", StockObservationResultEnum.NO_THEORETICAL_ENTRY.getCode());
        assertEquals("OBSERVATION_DATA_INSUFFICIENT",
                StockObservationResultEnum.OBSERVATION_DATA_INSUFFICIENT.getCode());
        assertEquals("OBSERVATION_COMPLETED", StockObservationResultEnum.OBSERVATION_COMPLETED.getCode());
    }

    @Test
    @DisplayName("信号事件DO_包含结果码和数据缺口字段")
    void signalEventDo_containsObservationResultFields() throws NoSuchFieldException {
        Field resultField = TornStockSignalEventDO.class.getDeclaredField("observationResult");
        Field incompleteField = TornStockSignalEventDO.class.getDeclaredField("observationDataIncomplete");

        assertNotNull(resultField);
        assertNotNull(incompleteField);
        assertEquals(String.class, resultField.getType());
        assertEquals(Boolean.class, incompleteField.getType());
        assertFalse(incompleteField.getType().isPrimitive());
    }
}
