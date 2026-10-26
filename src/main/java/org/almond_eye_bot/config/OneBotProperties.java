package org.almond_eye_bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "onebot")
public class OneBotProperties {

    private boolean enabled = false;
    
    private String path = "/onebot/ws";

    private String accessToken = "";

    private boolean groupRequireAt = true;
    
    private long timeout = 30000;
}
