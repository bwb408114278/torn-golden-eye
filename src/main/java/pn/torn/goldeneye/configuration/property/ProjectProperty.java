package pn.torn.goldeneye.configuration.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目属性
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.10
 */
@Data
@Component
@ConfigurationProperties(prefix = "project")
public class ProjectProperty {
    /**
     * 当前环境
     */
    private String env;
    /**
     * 群号
     */
    private long groupId;
    /**
     * 管理员ID
     */
    private List<Long> adminId;
}