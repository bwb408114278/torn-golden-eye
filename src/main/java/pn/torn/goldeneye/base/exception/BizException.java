package pn.torn.goldeneye.base.exception;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 机器人异常
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.11
 */
@Slf4j
@Getter
public class BizException extends RuntimeException {
    private final String msg;

    public BizException(String msg) {
        this.msg = msg;
    }

    public BizException(String msg, Throwable cause) {
        super(cause);
        this.msg = msg;
    }
}