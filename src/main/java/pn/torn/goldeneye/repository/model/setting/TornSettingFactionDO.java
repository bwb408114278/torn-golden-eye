package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn设置帮派表
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_setting_faction", autoResultMap = true)
public class TornSettingFactionDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 帮派名称
     */
    private String factionName;
    /**
     * 帮派简称
     */
    private String factionShortName;
    /**
     * 帮派别名
     */
    private String factionAlias;
    /**
     * QQ群号
     */
    private Long groupId;
    /**
     * 群聊管理员ID
     */
    private String groupAdminIds;
    /**
     * OC指挥官ID
     */
    private String ocCommanderIds;
    /**
     * 战斗指挥官ID
     */
    private String warCommanderIds;
    /**
     * 军需官ID
     */
    private String quartermasterIds;
    /**
     * 是否屏蔽消息
     */
    private Boolean msgBlock;
}