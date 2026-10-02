package pn.torn.goldeneye.torn.model.faction.oc.delay;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OC延误原因。
 * <p>
 * 编码用于落库与审计，展示名用于完成通知的原因行；两者都不承载判定逻辑。
 *
 * @author Bai
 * @version 1.6.7
 * @since 2026.10.02
 */
@Getter
@RequiredArgsConstructor
public enum OcDelayReasonEnum {
    /**
     * 成员在旅行（含起飞、滞留、返回，以及海外住院）。
     */
    TRAVEL("TRAVEL", "旅行"),
    /**
     * 成员在本土住院。
     */
    HOSPITAL("HOSPITAL", "住院"),
    /**
     * 成员在监狱。
     */
    JAIL("JAIL", "监狱"),
    /**
     * 成员缺少该岗位要求的道具。
     */
    ITEM("ITEM", "缺道具");

    /**
     * 落库编码。
     */
    private final String code;
    /**
     * 通知展示名。
     */
    private final String label;

    /**
     * 按落库编码查询延误原因。
     *
     * @param code 落库编码
     * @return 对应原因；编码未知时返回null
     */
    public static OcDelayReasonEnum codeOf(String code) {
        for (OcDelayReasonEnum value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }

        return null;
    }
}
