package pn.torn.goldeneye.base.ex;

/**
 * 机器人异常
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.11
 */
public class BotException extends RuntimeException {
    public BotException(Throwable cause) {
        super(cause);
    }
}