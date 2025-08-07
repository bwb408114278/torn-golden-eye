package pn.torn.goldeneye.constants.torn.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * OC状态枚举
 *
 * @author Bai
 * @version 0.1.0
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
     * 已完成
     */
    COMPLETED("Completed");

    private final String code;
}