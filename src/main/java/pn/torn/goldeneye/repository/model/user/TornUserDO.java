package pn.torn.goldeneye.repository.model.user;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * Torn用户表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_user", autoResultMap = true)
public class TornUserDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 昵称
     */
    private String nickname;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 注册日期
     */
    private LocalDate registerDate;
}