package org.almond_eye_bot.config;

import org.almond_eye_bot.handler.OneBotMessageHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "onebot", name = "enabled", havingValue = "true")
public class WebSocketConfig implements WebSocketConfigurer {

    private final OneBotMessageHandler oneBotMessageHandler;
    private final OneBotProperties properties;

    public WebSocketConfig(OneBotMessageHandler oneBotMessageHandler, OneBotProperties properties) {
        this.oneBotMessageHandler = oneBotMessageHandler;
        this.properties = properties;
        if (properties.getAccessToken().isBlank()) {
            throw new IllegalArgumentException("启用 OneBot 时必须设置 ONEBOT_ACCESS_TOKEN");
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(oneBotMessageHandler, properties.getPath())
                .addInterceptors(new OneBotHandshakeInterceptor(properties))
                .setAllowedOrigins("*");
    }
}
