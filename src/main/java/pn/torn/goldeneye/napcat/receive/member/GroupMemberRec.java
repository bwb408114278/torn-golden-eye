package pn.torn.goldeneye.napcat.receive.member;

import lombok.Data;

import java.util.List;

/**
 * 群聊成员返回体
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.31
 */
@Data
public class GroupMemberRec {
    /**
     * 群聊成员数据
     */
    private List<GroupMemberDataRec> data;
}