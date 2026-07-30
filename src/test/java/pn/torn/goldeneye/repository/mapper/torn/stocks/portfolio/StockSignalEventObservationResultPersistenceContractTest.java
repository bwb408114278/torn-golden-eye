package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒绝观察结果持久化的Liquibase、DO和Mapper契约测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("拒绝观察结果持久化契约测试")
class StockSignalEventObservationResultPersistenceContractTest {

    private static final String CHANGELOG =
            "db/changelog/1.0.1-2.0.0/1.2.0/stocks-portfolio.yaml";
    private static final String MAPPER =
            "mapper/torn/stocks/portfolio/TornStockSignalEventMapper.xml";

    @Test
    @DisplayName("原始建表changeSet_声明结果码和数据缺口字段")
    void changelog_declaresObservationResultColumns() throws IOException {
        String source = readResource(CHANGELOG);

        assertTrue(source.contains("name: observation_result"));
        assertTrue(source.contains("remarks: \"拒绝观察理论结果编码\""));
        assertTrue(source.contains("name: observation_data_incomplete"));
        assertTrue(source.contains("defaultValueBoolean: false"));
        assertTrue(source.contains("remarks: \"观察窗口是否存在数据缺口\""));
    }

    @Test
    @DisplayName("信号事件DO_声明结果持久化字段")
    void signalEventDo_declaresObservationResultColumns() {
        assertTrue(hasField("observationResult"));
        assertTrue(hasField("observationDataIncomplete"));
    }

    @Test
    @DisplayName("Mapper_提供只更新未结算事件的批量结果回写")
    void mapper_updatesOnlyUnresolvedEventsInBatch() throws IOException {
        String source = readResource(MAPPER);

        assertTrue(source.contains("updateObservationResultsByIds"));
        assertTrue(source.contains("resolved_at IS NULL"));
        assertTrue(source.contains("observation_result"));
        assertTrue(source.contains("observation_data_incomplete"));
    }

    private boolean hasField(String fieldName) {
        try {
            TornStockSignalEventDO.class.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException exception) {
            return false;
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream inputStream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
