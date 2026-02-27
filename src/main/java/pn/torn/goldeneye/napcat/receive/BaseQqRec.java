package pn.torn.goldeneye.napcat.receive;

import lombok.Data;

/**
 * QQ接受消息基础返回体
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.25
 */
@Data
public class BaseQqRec {
    /**
     * 状态 ok/failed
     */
    private String status;
}