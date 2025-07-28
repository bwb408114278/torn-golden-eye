package pn.torn.goldeneye.configuration.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Torn Api Keys
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.28
 */
@Data
@Component
@ConfigurationProperties(prefix = "golden-eye.api")
public class TornApiProperty {
    /**
     * 管理员ID
     */
    private List<String> key;
}