package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.Arrays;

/**
 * OC新队规划模式。
 */
public enum OcPlanMode {
    CONSERVATIVE("保守"),
    BALANCED("均衡"),
    PROFIT("收益");

    private final String command;

    OcPlanMode(String command) {
        this.command = command;
    }

    /**
     * 获取规划模式对应的二级指令。
     *
     * @return 二级指令文本
     */
    public String getCommand() {
        return command;
    }

    /**
     * 解析OC新队二级指令。
     *
     * @param command 二级指令文本
     * @return 对应的规划模式
     * @throws IllegalArgumentException 指令为空或不受支持时抛出
     */
    public static OcPlanMode parse(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("必须提供OC新队二级指令");
        }
        String normalized = command.trim();
        return Arrays.stream(values())
                .filter(mode -> mode.command.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的OC新队二级指令: " + command));
    }
}
