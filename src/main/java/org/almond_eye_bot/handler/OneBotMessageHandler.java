package org.almond_eye_bot.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.almond_eye_bot.client.OneBotClient;
import org.almond_eye_bot.service.OneBotMessageService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OneBotMessageHandler extends TextWebSocketHandler {
    private final OneBotClient client;
    private final OneBotMessageService service;
    private final org.almond_eye_bot.service.BotConfigurationService configuration;
    private final Map<String, Long> processed = new LinkedHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "onebot-message");
                thread.setDaemon(true);
                return thread;
            });

    public OneBotMessageHandler(OneBotClient client, OneBotMessageService service,
                               org.almond_eye_bot.service.BotConfigurationService configuration) {
        this.client = client;
        this.service = service;
        this.configuration = configuration;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("OneBot 反向 WebSocket 已连接: {}", session.getId());
        client.sendRequest(session, "get_login_info", Map.of()).thenAccept(login -> {
            JSONObject account = login.getJSONObject("data");
            if (account == null || account.getString("user_id") == null) return;
            String self = account.getString("user_id");
            client.bindAccount(self,session);
            client.sendRequest(session, "get_group_list", Map.of()).thenAccept(response -> {
                var groups = response.getJSONArray("data");
                if (groups == null) return;
                for (int i = 0; i < groups.size(); i++) {
                    var group = groups.getJSONObject(i);
                    configuration.observeGroup(self, group.getString("group_id"), group.getString("group_name"));
                }
            }).exceptionally(error -> { log.warn("读取 OneBot 群列表失败", error); return null; });
        }).exceptionally(error -> { log.warn("读取 OneBot 登录信息失败", error); return null; });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JSONObject event = JSON.parseObject(message.getPayload());
            if (event == null) return;
            if (!event.containsKey("post_type")) {
                client.handleResponse(session, event);
                return;
            }
            if (!"message".equals(event.getString("post_type"))) return;
            client.bindAccount(event.getString("self_id"),session);
            String key = messageKey(event);
            if (key != null && !claim(key)) return;
            try {
                // Keep API replies and heartbeats flowing while AI / Wiki calls run.
                workers.execute(() -> {
                    try {
                        service.handleEvent(event, (action, params) -> {
                            // Socket write order is not QQ delivery order: quoted messages may take longer.
                            // Wait on this worker only; the WebSocket thread must keep handling API responses.
                            try {
                                client.sendRequest(session, action, params).get();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new java.util.concurrent.CompletionException(interrupted);
                            } catch (java.util.concurrent.ExecutionException failed) {
                                throw new java.util.concurrent.CompletionException(failed.getCause());
                            }
                        });
                    } catch (Exception e) {
                        log.error("处理 OneBot 事件失败", e);
                        forget(key);
                    }
                });
            } catch (RejectedExecutionException e) {
                forget(key);
                log.warn("OneBot 消息队列已满，丢弃本次事件");
            }
        } catch (Exception e) {
            log.warn("无效的 OneBot 消息", e);
        }
    }

    private String messageKey(JSONObject event) {
        if (event.get("message_id") == null) return null;
        return event.getString("self_id") + ":" + event.getString("message_type") + ":"
                + event.getString("group_id") + ":" + event.getString("user_id") + ":" + event.getString("message_id");
    }

    private synchronized boolean claim(String key) {
        long now = System.currentTimeMillis();
        processed.entrySet().removeIf(entry -> now - entry.getValue() > 600_000);
        if (processed.containsKey(key)) return false;
        if (processed.size() >= 10_000) processed.remove(processed.keySet().iterator().next());
        processed.put(key, now);
        return true;
    }

    private synchronized void forget(String key) {
        if (key != null) processed.remove(key);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        client.disconnected(session);
        log.info("OneBot 连接关闭: {}, {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        client.disconnected(session);
        session.close(CloseStatus.SERVER_ERROR);
    }

    @PreDestroy
    public void destroy() {
        workers.shutdownNow();
    }
}
