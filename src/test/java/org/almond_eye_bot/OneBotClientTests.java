package org.almond_eye_bot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.OneBotClient;
import org.almond_eye_bot.config.OneBotProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OneBotClientTests {
    @Test
    void responseMustComeFromOriginalConnection() throws Exception {
        OneBotClient client = new OneBotClient(new OneBotProperties());
        WebSocketSession session = session("a");
        var future = client.sendRequest(session, "get_login_info", Map.of());
        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JSONObject request = JSON.parseObject(captor.getValue().getPayload());
        JSONObject response = JSONObject.of("echo", request.getString("echo"), "retcode", 0, "status", "ok");
        client.handleResponse(session("b"), response);
        assertFalse(future.isDone());
        client.handleResponse(session, response);
        assertEquals(response, future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void disconnectAndTimeoutCompletePendingRequests() throws Exception {
        OneBotProperties properties = new OneBotProperties();
        properties.setTimeout(30);
        OneBotClient client = new OneBotClient(properties);
        WebSocketSession session = session("a");
        var disconnected = client.sendRequest(session, "get_login_info", Map.of());
        client.disconnected(session);
        assertTrue(disconnected.isCompletedExceptionally());
        var timeout = client.sendRequest(session, "get_login_info", Map.of());
        assertThrows(ExecutionException.class, () -> timeout.get(2, TimeUnit.SECONDS));
    }

    @Test
    void failedActionCompletesExceptionally() throws Exception {
        OneBotClient client = new OneBotClient(new OneBotProperties());
        WebSocketSession session = session("a");
        var result = client.sendRequest(session, "send_group_msg", Map.of());
        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String echo = JSON.parseObject(captor.getValue().getPayload()).getString("echo");
        client.handleResponse(session, JSONObject.of("echo", echo, "retcode", 100, "status", "failed"));
        assertTrue(result.isCompletedExceptionally());
    }

    @Test void accountRegistryKeepsLatestConnectionAndSeparatesAccounts() {
        var client=new OneBotClient(new OneBotProperties());var first=session("old");var latest=session("new");var other=session("other");
        client.bindAccount("100",first);client.bindAccount("100",latest);client.bindAccount("101",other);
        client.disconnected(first);assertTrue(client.accountConnected("100"));assertTrue(client.accountConnected("101"));
        client.disconnected(latest);assertFalse(client.accountConnected("100"));assertTrue(client.accountConnected("101"));
    }
    @Test void scheduledSendAwaitsAckAndUsesLiteralSegments() throws Exception {
        var client=new OneBotClient(new OneBotProperties());var session=session("push");client.bindAccount("100",session);
        doAnswer(call->{var body=JSON.parseObject(((TextMessage)call.getArgument(0)).getPayload());
            assertEquals("send_group_msg",body.getString("action"));assertEquals(300,body.getJSONObject("params").getIntValue("group_id"));
            assertEquals("text",body.getJSONObject("params").getJSONArray("message").getJSONObject(0).getString("type"));
            client.handleResponse(session,JSONObject.of("echo",body.getString("echo"),"retcode",0,"status","ok"));return null;
        }).when(session).sendMessage(any());
        client.sendToAccount("100",true,"300","[CQ:at,qq=all]");
        assertThrows(java.io.IOException.class,()->client.sendToAccount("999",true,"300","text"));
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
