package pn.torn.goldeneye.torn.model.faction.member;

import lombok.Data;

import java.util.List;

/**
 * Torn帮派成员列表响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.04
 */
@Data
public class TornFactionMemberListVO {
    /**
     * 成员列表
     */
    private List<TornFactionMemberVO> members;
}
