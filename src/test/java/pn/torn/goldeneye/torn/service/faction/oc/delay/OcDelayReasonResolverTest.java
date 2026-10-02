package pn.torn.goldeneye.torn.service.faction.oc.delay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayReasonEnum;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;
import pn.torn.goldeneye.torn.service.faction.oc.delay.OcDelayReasonResolver.OcDelayReason;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OC延误原因判定测试。
 *
 * @author Bai
 * @version 1.6.7
 * @since 2026.10.02
 */
@DisplayName("OC延误原因判定测试")
class OcDelayReasonResolverTest {
    private final OcDelayReasonResolver resolver = new OcDelayReasonResolver();

    @ParameterizedTest(name = "{0}")
    @MethodSource("statusReasonCases")
    @DisplayName("状态类命中：旅行/滞留/本土住院/监狱按优先级归因")
    void resolve_shouldResolveStatusReason(String displayName, String state, String description,
                                           OcDelayReasonEnum expected) {
        Optional<OcDelayReason> result = resolver.resolve(buildStatus(state, description), null, null);

        assertEquals(Optional.of(new OcDelayReason(expected, null)), result);
    }

    @Test
    @DisplayName("海外住院：描述含旅行目的地时归为旅行而不是住院")
    void resolve_shouldTreatAbroadHospitalAsTravel() {
        Optional<OcDelayReason> result = resolver.resolve(
                buildStatus(TornUserStatusEnum.HOSPITAL.getCode(), "In hospital in Mexico"), null, null);

        assertEquals(Optional.of(new OcDelayReason(OcDelayReasonEnum.TRAVEL, null)), result);
    }

    @Test
    @DisplayName("缺道具：状态正常且道具明确不可用时命中缺道具")
    void resolve_shouldResolveMissingItem() {
        Optional<OcDelayReason> result = resolver.resolve(
                buildStatus(TornUserStatusEnum.OKAY.getCode(), "Okay"), 1430, Boolean.FALSE);

        assertEquals(Optional.of(new OcDelayReason(OcDelayReasonEnum.ITEM, 1430)), result);
    }

    @Test
    @DisplayName("状态优先：状态与缺道具同时命中时主原因为状态并保留道具ID")
    void resolve_shouldPreferStatusReasonAndKeepItemId() {
        Optional<OcDelayReason> result = resolver.resolve(
                buildStatus(TornUserStatusEnum.JAIL.getCode(), "In jail"), 1430, Boolean.FALSE);

        assertEquals(Optional.of(new OcDelayReason(OcDelayReasonEnum.JAIL, 1430)), result);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notBlockedCases")
    @DisplayName("不命中：正常或未定义状态且道具未确认缺失时不产生条目")
    void resolve_shouldReturnEmptyWhenNotBlocked(String displayName, TornUserStatusVO status,
                                                 Integer requiredItemId, Boolean requiredItemAvailable) {
        assertTrue(resolver.resolve(status, requiredItemId, requiredItemAvailable).isEmpty());
    }

    static Stream<Arguments> statusReasonCases() {
        return Stream.of(
                Arguments.of("旅行中", TornUserStatusEnum.TRAVELING.getCode(), "Traveling to Japan",
                        OcDelayReasonEnum.TRAVEL),
                Arguments.of("滞留国外", TornUserStatusEnum.ABROAD.getCode(), "Abroad in Japan",
                        OcDelayReasonEnum.TRAVEL),
                Arguments.of("本土住院", TornUserStatusEnum.HOSPITAL.getCode(), "In hospital for 2 hours",
                        OcDelayReasonEnum.HOSPITAL),
                Arguments.of("监狱", TornUserStatusEnum.JAIL.getCode(), "In jail for 30 minutes",
                        OcDelayReasonEnum.JAIL));
    }

    static Stream<Arguments> notBlockedCases() {
        return Stream.of(
                Arguments.of("状态正常且道具可用",
                        buildStatus(TornUserStatusEnum.OKAY.getCode(), "Okay"), 1430, Boolean.TRUE),
                Arguments.of("状态正常且无道具要求",
                        buildStatus(TornUserStatusEnum.OKAY.getCode(), "Okay"), null, null),
                Arguments.of("状态正常且道具可用性不确认",
                        buildStatus(TornUserStatusEnum.OKAY.getCode(), "Okay"), 1430, null),
                Arguments.of("状态正常且道具可用性为true",
                        buildStatus(TornUserStatusEnum.OKAY.getCode(), "Okay"), 1430, Boolean.TRUE),
                Arguments.of("未定义状态且无道具要求", buildStatus("Federal", "Federal"), null, null),
                Arguments.of("未定义状态且道具可用", buildStatus("Federal", "Federal"), 1430, Boolean.TRUE),
                Arguments.of("成员状态缺失且道具可用", null, 1430, Boolean.TRUE));
    }

    private static TornUserStatusVO buildStatus(String state, String description) {
        TornUserStatusVO status = new TornUserStatusVO();
        status.setState(state);
        status.setDescription(description);
        return status;
    }
}
