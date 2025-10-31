package pn.torn.goldeneye.constants.torn.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * OC状态枚举
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.29
 */
@AllArgsConstructor
@Getter
public enum TornOcStatusEnum {
    /**
     * 招募中
     */
    RECRUITING("Recruiting"),
    /**
     * 计划中
     */
    PLANNING("Planning"),
    /**
     * 成功
     */
    SUCCESSFUL("Successful"),
    /**
     * 失败
     */
    FAILURE("Failure");

    private final String code;

    public static List<String> getCompleteStatusList() {
        return List.of(SUCCESSFUL.getCode(), FAILURE.getCode());
    }
}