package pn.torn.goldeneye.torn.model.user.master;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * 师父排队附近排名输出结果
 *
 * @author Bai
 * @version 1.1.2
 * @since 2026.05.18
 */
@Getter
@ToString
@AllArgsConstructor
public class MasterQueueAroundVO {
    /**
     * 当前用户排名
     */
    private int rank;
    /**
     * 当前用户信息
     */
    private MasterQueueVO currentUser;
    /**
     * 当前用户附近的排队信息（最多5人，包含自己）
     */
    private List<MasterQueueVO> aroundUsers;
}