package pn.torn.goldeneye.torn.model.user.master;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 师父排队响应参数
 *
 * @author Bai
 * @version 1.1.2
 * @since 2026.05.18
 */
@Getter
@ToString
@AllArgsConstructor
public class MasterQueueVO {
    /**
     * 用户排名
     */
    private int rank;
    /**
     * 用户ID
     */
    private long userId;
    /**
     * 用户昵称
     */
    private String nickname;
}