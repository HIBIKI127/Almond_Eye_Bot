package org.almond_eye_bot.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public class OneBotHandshakeInterceptor implements HandshakeInterceptor {
    private final OneBotProperties properties;

    public OneBotHandshakeInterceptor(OneBotProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String expected = "Bearer " + properties.getAccessToken();
        String actual = request.getHeaders().getFirst("Authorization");
        if (!properties.isEnabled() || properties.getAccessToken().isBlank() || actual == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                         actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
