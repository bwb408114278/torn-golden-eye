package pn.torn.goldeneye.torn.model.faction;

import lombok.Getter;
import lombok.ToString;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;

import java.util.Arrays;
import java.util.List;

/**
 * Torn帮派逻辑对象
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.25
 */
@Getter
@ToString
public class TornFactionBO {
    /**
     * ID
     */
    private final Long id;
    /**
     * 帮派名称
     */
    private final String factionName;
    /**
     * 帮派简称
     */
    private final String factionShortName;
    /**
     * 帮派别名
     */
    private final String factionAlias;
    /**
     * QQ群号
     */
    private final Long groupId;
    /**
     * 群聊管理员ID
     */
    private final String groupAdminIds;
    /**
     * OC指挥官ID
     */
    private final String ocCommanderIds;
    /**
     * 战斗指挥官ID
     */
    private final String warCommanderIds;
    /**
     * 军需官ID
     */
    private final String quartermasterIds;
    /**
     * 是否屏蔽消息
     */
    private final Boolean msgBlock;
    /**
     * 所有管理员QQ
     */
    private final List<Long> allAdminQq;
    /**
     * 是否担任管理员
     */
    private final Boolean isAdmin;

    public TornFactionBO(TornSettingFactionDO faction) {
        this.id = faction.getId();
        this.factionName = faction.getFactionName();
        this.factionShortName = faction.getFactionShortName();
        this.factionAlias = faction.getFactionAlias();
        this.groupId = faction.getGroupId();
        this.groupAdminIds = faction.getGroupAdminIds();
        this.ocCommanderIds = faction.getOcCommanderIds();
        this.warCommanderIds = faction.getWarCommanderIds();
        this.quartermasterIds = faction.getQuartermasterIds();
        this.msgBlock = faction.getMsgBlock();
        this.isAdmin = faction.getIsAdmin();

        if (StringUtils.hasText(faction.getAllAdminQq())) {
            this.allAdminQq = Arrays.stream(faction.getAllAdminQq().split(",")).map(Long::parseLong).toList();
        } else {
            this.allAdminQq = List.of();
        }
    }
}
