package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.*;
import org.almond_eye_bot.config.BotConfiguration.Settings;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiQuietReplyTests {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private BotConfigurationService memoryStore;
    private MemorySummaryQueue summaryQueue;
    @org.junit.jupiter.api.AfterEach void close() throws Exception {
        if (memoryStore != null) memoryStore.close();
        if (summaryQueue != null) summaryQueue.close();
    }
    @Test void failedAndEmptyRequestsDoNotBecomeReplyTextOrPolluteHistory() throws Exception {
        var client = mock(DeepSeekClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.createMessage(anyString(), anyString())).thenAnswer(call ->
                JSONObject.of("role", call.getArgument(0), "content", call.getArgument(1)));
        when(client.createMessageHistory(anyString())).thenAnswer(call ->
                new ArrayList<>(List.of(JSONObject.of("role", "system", "content", "测试系统消息"))));
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false))).thenReturn("成功回复", null, " \n ")
                .thenThrow(new IllegalStateException("连接失败"));
        when(client.chat(anyList(), eq("deepseek-reasoner"), eq(false))).thenThrow(new IllegalStateException("异常"));
        var ai = new AiChatService();
        ReflectionTestUtils.setField(ai, "deepSeekClient", client);
        memoryStore = new BotConfigurationService(directory.toString(), new org.almond_eye_bot.config.OneBotProperties());
        ReflectionTestUtils.setField(ai, "configuration", memoryStore);
        ReflectionTestUtils.setField(ai, "memory", new ConversationMemoryService(memoryStore, client));
        assertEquals("成功回复", ai.chat("user", "成功问题"));
        assertEquals(3, ai.getHistorySize("user"));
        for (int i = 0; i < 3; i++) {
            assertNull(ai.chat("user", "失败问题"));
            assertEquals(3, ai.getHistorySize("user"));
        }
        assertNull(ai.chatWithReasoning("user", "深度问题"));
        assertEquals(3, ai.getHistorySize("user"));
    }

    @Test void officialQqSendsExactlyOneSuccessfulResultAndNoFailureMessages() throws Exception {
        var ai = mock(AiChatService.class);
        var api = mock(QQApiClient.class);
        var configuration = mock(BotConfigurationService.class);
        when(configuration.settings()).thenReturn(new Settings(true, true, true, true, false));
        var messages = new QQMessageService();
        when(configuration.match(anyString())).thenReturn(Optional.of(new org.almond_eye_bot.config.BotConfiguration.MatchedReply("", null, "AI", "问题", "AI")));
        ReflectionTestUtils.setField(messages, "replies", new BotReplyService(ai, mock(LocalImageService.class), mock(WikiService.class), configuration, mock(GroupAutoReplyService.class)));
        ReflectionTestUtils.setField(messages, "qqApi", api);
        ReflectionTestUtils.setField(messages, "configuration", configuration);
        when(ai.chat(anyString(), anyString())).thenReturn("仅最终回复", null, " ")
                .thenThrow(new IllegalStateException("模拟失败"));
        for (int i = 0; i < 4; i++)
            ReflectionTestUtils.invokeMethod(messages, "handleMessage", "user", "AI 问题", "id", "private");
        verify(api).sendPrivateMsg("user", "仅最终回复");
        verifyNoMoreInteractions(api);
    }

    @Test void webAiReturnsNoChatContentOnFailure() throws Exception {
        var ai = mock(AiChatService.class);
        var messages = new WebMessageService();
        var configuration = mock(BotConfigurationService.class);
        summaryQueue = new MemorySummaryQueue();
        when(configuration.summaries()).thenReturn(summaryQueue);
        when(configuration.settings()).thenReturn(new Settings(true, true, true, true, false));
        when(configuration.match(anyString())).thenReturn(Optional.of(new org.almond_eye_bot.config.BotConfiguration.MatchedReply("", null, "AI", "问题", "AI")));
        ReflectionTestUtils.setField(messages, "configuration", configuration);
        ReflectionTestUtils.setField(messages, "replies", new BotReplyService(ai, mock(LocalImageService.class), mock(WikiService.class), configuration, mock(GroupAutoReplyService.class)));
        when(ai.chat(anyString(), anyString())).thenReturn(null, " ").thenThrow(new IllegalStateException("模拟失败"));
        for (int i = 0; i < 3; i++) {
            Map<String, Object> result = messages.handleMessage("user", "AI 问题");
            assertEquals("none", result.get("type"));
            assertFalse(result.containsKey("content"));
            assertFalse(result.containsKey("thinking"));
        }
    }
}
