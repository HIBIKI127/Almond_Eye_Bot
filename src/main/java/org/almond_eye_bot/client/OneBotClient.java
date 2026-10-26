package org.almond_eye_bot.client;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.almond_eye_bot.config.OneBotProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** OneBot actions return over the connection that delivered the event. */
@Slf4j
@Component
public class OneBotClient {
    private record Pending(String sessionId, CompletableFuture<JSONObject> future) {}
    private final OneBotProperties properties;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final Map<String,WebSocketSession> accounts=new ConcurrentHashMap<>();
    public void bindAccount(String self,WebSocketSession session){if(self!=null&&self.matches("[1-9][0-9]*"))accounts.put(self,session);}
    public boolean accountConnected(String self){var s=accounts.get(self);return s!=null&&s.isOpen();}
    public void sendToAccount(String self,boolean group,String target,String text) throws Exception {
        var session=accounts.get(self);if(session==null||!session.isOpen())throw new IOException("OneBot账号未连接");
        sendRequest(session,group?"send_group_msg":"send_private_msg",Map.of(group?"group_id":"user_id",Long.parseLong(target),
                "message",java.util.List.of(Map.of("type","text","data",Map.of("text",text))))).get();
    }

    public OneBotClient(OneBotProperties properties) {
        this.properties = properties;
        if (properties.getTimeout() <= 0) throw new IllegalArgumentException("onebot.timeout 必须大于 0");
    }

    public CompletableFuture<JSONObject> sendRequest(WebSocketSession session, String action, Map<String, Object> params) {
        String echo = UUID.randomUUID().toString();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(echo, new Pending(session.getId(), future));
        future.orTimeout(properties.getTimeout(), TimeUnit.MILLISECONDS).whenComplete((response, error) -> {
            pending.remove(echo);
            if (error != null) log.warn("OneBot action {} 失败: {}", action, error.toString());
        });
        JSONObject request = new JSONObject();
        request.put("action", action);
        request.put("params", params);
        request.put("echo", echo);
        try {
            // Spring WebSocket sessions do not support concurrent writes.
            synchronized (session) {
                if (!session.isOpen()) throw new IOException("OneBot 连接已关闭");
                session.sendMessage(new TextMessage(request.toJSONString()));
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public void handleResponse(WebSocketSession session, JSONObject response) {
        String echo = response.getString("echo");
        if (echo == null) return;
        Pending request = pending.get(echo);
        if (request == null || !request.sessionId().equals(session.getId())) return;
        if (response.getIntValue("retcode", -1) == 0 && !"failed".equals(response.getString("status"))) {
            request.future().complete(response);
        } else {
            request.future().completeExceptionally(new IOException("retcode=" + response.getString("retcode")
                    + ", " + response.getString("message")));
        }
    }

    public void disconnected(WebSocketSession session) {
        accounts.entrySet().removeIf(e->e.getValue()==session);
        pending.values().stream().filter(p -> p.sessionId().equals(session.getId()))
                .forEach(p -> p.future().completeExceptionally(new IOException("OneBot 连接断开")));
    }
}
