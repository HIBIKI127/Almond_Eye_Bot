package org.almond_eye_bot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"onebot.enabled=true", "onebot.access-token=test-token"})
class OneBotWebSocketTests {
    @org.springframework.beans.factory.annotation.Autowired
    org.almond_eye_bot.service.BotConfigurationService configuration;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.almond_eye_bot.client.DeepSeekClient ai;
    @LocalServerPort
    private int port;

    private URI address() { return URI.create("ws://127.0.0.1:" + port + "/onebot/ws"); }

    @Test
    void rejectsMissingAndWrongToken() {
        HttpClient http = HttpClient.newHttpClient();
        {
            for (String token : new String[]{"", "wrong"}) {
                var builder = http.newWebSocketBuilder();
                if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
                var connection = builder.buildAsync(address(), new WebSocket.Listener() {});
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> connection.get(5, TimeUnit.SECONDS));
                assertInstanceOf(WebSocketHandshakeException.class, error.getCause());
                assertEquals(401, ((WebSocketHandshakeException) error.getCause()).getResponse().statusCode());
            }
        }
    }

    @Test
    void privateAndGroupRepliesUseSameSocketAndDuplicatesAreIgnored() throws Exception {
        BlockingQueue<JSONObject> received = new LinkedBlockingQueue<>();
        HttpClient http = HttpClient.newHttpClient();
        {
            WebSocket socket = http.newWebSocketBuilder().header("Authorization", "Bearer test-token")
                    .buildAsync(address(), new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();
                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                JSONObject action = JSON.parseObject(buffer.toString());
                                buffer.setLength(0);
                                Object responseData = switch (action.getString("action")) {
                                    case "get_login_info" -> JSONObject.of("user_id", 100);
                                    case "get_group_list" -> com.alibaba.fastjson2.JSONArray.of(JSONObject.of("group_id", 300, "group_name", "测试群"));
                                    default -> JSONObject.of();
                                };
                                CompletionStage<?> acknowledgement = ws.sendText(JSONObject.of("status", "ok", "retcode", 0,
                                        "echo", action.get("echo"), "data", responseData).toJSONString(), true)
                                        .thenRun(() -> received.add(action));
                                ws.request(1);
                                return acknowledgement;
                            }
                            ws.request(1);
                            return CompletableFuture.completedFuture(null);
                        }
                    }).get(5, TimeUnit.SECONDS);
            try {
                JSONObject login = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(login);
                assertEquals("get_login_info", login.getString("action"));
                JSONObject discovery = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(discovery);
                assertEquals("get_group_list", discovery.getString("action"));
                String event = "{\"post_type\":\"message\",\"self_id\":100,\"user_id\":200,"
                        + "\"message_type\":\"private\",\"message_id\":1,\"message\":\"/ping\"}";
                socket.sendText(event, true).get(5, TimeUnit.SECONDS);
                JSONObject reply = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(reply);
                assertEquals("send_private_msg", reply.getString("action"));
                assertEquals("pong", reply.getJSONObject("params").getJSONArray("message")
                        .getJSONObject(0).getJSONObject("data").getString("text"));
                assertEquals(1, reply.getJSONObject("params").getJSONArray("message").size());
                socket.sendText(event, true).get(5, TimeUnit.SECONDS);
                assertNull(received.poll(300, TimeUnit.MILLISECONDS));
                // Discovered groups remain silent even for @ + keyword until explicitly enabled.
                socket.sendText("{\"post_type\":\"message\",\"self_id\":100,\"user_id\":200,"
                        + "\"message_type\":\"group\",\"group_id\":300,\"message_id\":2,"
                        + "\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"100\"}},"
                        + "{\"type\":\"text\",\"data\":{\"text\":\" /ping\"}}]}", true).get(5, TimeUnit.SECONDS);
                assertNull(received.poll(300, TimeUnit.MILLISECONDS));
                assertFalse(configuration.group("100", "300").enabled());
                configuration.saveGroup(GroupAutoReplyTests.policy("300", "SMART", 10, 1));
                socket.sendText("{\"post_type\":\"message\",\"self_id\":100,\"user_id\":200,\"message_type\":\"group\",\"group_id\":300,\"message_id\":20,\"message\":\"ping\"}", true).get(5, TimeUnit.SECONDS);
                JSONObject group = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(group);
                assertEquals("send_group_msg", group.getString("action"));
                assertEquals(300L, group.getJSONObject("params").getLongValue("group_id"));
                assertEquals(1, group.getJSONObject("params").getJSONArray("message").size());
                socket.sendText("{\"post_type\":\"message\",\"self_id\":100,\"user_id\":200,\"message_type\":\"group\",\"group_id\":300,\"message_id\":22,\"message\":\"[CQ:at,qq=100] ping\"}", true).get(5, TimeUnit.SECONDS);
                JSONObject mentioned = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(mentioned);
                var mentionedSegments = mentioned.getJSONObject("params").getJSONArray("message");
                assertEquals(2, mentionedSegments.size());
                assertEquals("reply", mentionedSegments.getJSONObject(0).getString("type"));
                assertEquals("22", mentionedSegments.getJSONObject(0).getJSONObject("data").getString("id"));
                assertEquals("pong", mentionedSegments.getJSONObject(1).getJSONObject("data").getString("text"));
                org.mockito.Mockito.when(ai.isConfigured()).thenReturn(true);
                org.mockito.Mockito.when(ai.chat(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(64))).thenReturn("免 @ 回复");
                socket.sendText("{\"post_type\":\"message\",\"self_id\":100,\"user_id\":200,\"message_type\":\"group\",\"group_id\":300,\"message_id\":3,\"message\":\"怎么做？\"}", true).get(5, TimeUnit.SECONDS);
                JSONObject automatic = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(automatic);
                assertEquals(1, automatic.getJSONObject("params").getJSONArray("message").size());
                assertEquals("免 @ 回复", automatic.getJSONObject("params").getJSONArray("message").getJSONObject(0).getJSONObject("data").getString("text"));
                socket.sendText("{\"post_type\":\"message\",\"self_id\":100,\"user_id\":200,\"message_type\":\"group\",\"group_id\":301,\"message_id\":4,\"message\":\"怎么做？\"}", true).get(5, TimeUnit.SECONDS);
                assertNull(received.poll(300, TimeUnit.MILLISECONDS));
                var current = configuration.group("100", "300");
                configuration.saveGroup(new org.almond_eye_bot.config.BotConfiguration.GroupPolicy(
                        current.selfId(), current.groupId(), current.groupName(), false, current.mode(), current.keywords(),
                        current.cooldownSeconds(), current.dailyLimit(), current.maxInputChars(), current.maxOutputTokens(),
                        current.contextTurns(), current.followUpSeconds(), 0));
                socket.sendText("{\"post_type\":\"message\",\"self_id\":100,\"user_id\":200,\"message_type\":\"group\",\"group_id\":300,\"message_id\":21,\"message\":\"ping\"}", true).get(5, TimeUnit.SECONDS);
                assertNull(received.poll(300, TimeUnit.MILLISECONDS));
                org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(1)).chat(org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt());
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            }
        }
    }
}

