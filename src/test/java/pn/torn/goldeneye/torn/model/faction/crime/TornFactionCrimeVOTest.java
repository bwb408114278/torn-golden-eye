package pn.torn.goldeneye.torn.model.faction.crime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Torn OC Crime DTO测试，验证外部创建时间字段的真实映射和缺失字段的关闭语义。
 */
@DisplayName("Torn OC Crime DTO测试")
class TornFactionCrimeVOTest {
    private static final long CREATED_AT = 1787211787L;

    @Test
    @DisplayName("created_at映射并透传到OC数据库模型")
    void createdAt_shouldMapAndConvertToOcData() throws JsonProcessingException {
        String json = "{\"id\":2065869,\"name\":\"Break the Bank\",\"difficulty\":5,"
                + "\"created_at\":" + CREATED_AT + ",\"slots\":[{\"position\":\"Thief\","
                + "\"checkpoint_pass_rate\":74,\"position_info\":{\"number\":1}}]}";

        TornFactionCrimeVO vo = readJson(json);
        TornFactionOcDO result = vo.convert2DO(2095L, Map.of());

        assertThat(vo.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(result.getTornCreatedAt()).isEqualTo(DateTimeUtils.convertToDateTime(CREATED_AT));
        assertThat(vo.getSlots().getFirst().getPosition()).isEqualTo("Thief");
        assertThat(vo.getSlots().getFirst().getCheckpointPassRate()).isEqualTo(74);
    }

    @Test
    @DisplayName("缺少created_at时不使用本地创建时间回退")
    void missingCreatedAt_shouldRemainNull() throws JsonProcessingException {
        TornFactionCrimeVO vo = readJson("{\"id\":2065869,\"name\":\"Break the Bank\"}");

        TornFactionOcDO result = vo.convert2DO(2095L, Map.of());
        result.setCreateTime(LocalDateTime.now());

        assertThat(vo.getCreatedAt()).isNull();
        assertThat(result.getTornCreatedAt()).isNull();
    }

    @Test
    @DisplayName("旧create_at字段不作为Torn真实创建时间")
    void legacyCreateAt_shouldNotMap() throws JsonProcessingException {
        TornFactionCrimeVO vo = readJson("{\"id\":2065869,\"create_at\":" + CREATED_AT + "}");

        assertThat(vo.getCreatedAt()).isNull();
    }

    private TornFactionCrimeVO readJson(String json) throws JsonProcessingException {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
                .readValue(json, TornFactionCrimeVO.class);
    }
}
