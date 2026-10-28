package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * OC流程图岗位编码归一化。
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.27
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcFlowSlotCodes {
    /**
     * 抹平配置与Torn岗位名的空白差异，例如CatBurglar#1与Cat Burglar#1。
     *
     * @param slotCode 岗位编码
     * @return 归一化编码；空输入返回空串
     */
    public static String normalize(String slotCode) {
        return slotCode == null ? "" : slotCode.replaceAll("[^0-9A-Za-z#]", "").toUpperCase(Locale.ROOT);
    }
}
