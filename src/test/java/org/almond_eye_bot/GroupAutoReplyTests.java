package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupAutoReplyTests {
    @TempDir Path directory;
    BotConfigurationService store;
    DeepSeekClient client;
    Clock clock;
    GroupAutoReplyService service;
    Instant now = Instant.parse("2026-10-23T15:59:50Z");

    static GroupPolicy policy(String group, String mode, int daily, int turns) {
        return new GroupPolicy("100", group, "测试群", true, mode, "小杏", 30, daily, 100, 64, turns, 60, 0);
    }
    @BeforeEach void setup() throws Exception {
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        client = mock(DeepSeekClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.chat(anyList(), anyString(), eq(false), anyInt())).thenReturn("你好");
        clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now);
        when(clock.millis()).thenAnswer(call -> now.toEpochMilli());
        service = new GroupAutoReplyService(store, client, clock);
    }
    @AfterEach void close() throws Exception { store.close(); }
    boolean reply(String group, String user, String text) { return service.reply("100", group, user, text, false, false, false).isPresent(); }

    @Test void onlyExplicitlyEnabledGroupsReachAi() {
        assertFalse(reply("300", "200", "你好"));
        assertFalse(store.observeGroup("100", "300", "新群").enabled());
        assertFalse(reply("300", "200", "你好"));
        store.saveGroup(policy("300", "SMART", 10, 1));
        assertFalse(service.reply("100", "300", "200", "怎么做？", false, true, false).isPresent());
        assertFalse(reply("300", "200", "问".repeat(101)));
        verify(client, never()).chat(anyList(), anyString(), anyBoolean(), anyInt());
        assertTrue(reply("300", "200", "我想吃什么"));
        verify(client).chat(anyList(), eq("deepseek-chat"), eq(false), eq(64));
        assertTrue(store.observeGroup("100", "300", "新名字").enabled());
    }

    @Test void cooldownDailyQuotaAndMidnightSurviveRestart() throws Exception {
        store.saveGroup(policy("300", "SMART", 1, 1));
        assertTrue(store.claimGroupCall("100", "300", now));
        store.close(); store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        assertFalse(store.claimGroupCall("100", "300", now.plusSeconds(15))); // next day, still cooling down
        assertTrue(store.claimGroupCall("100", "300", now.plusSeconds(31)));
        assertFalse(store.claimGroupCall("100", "300", now.plusSeconds(100)));
        store.saveGroup(policy("301", "SMART", 1, 1));
        assertTrue(store.claimGroupCall("100", "301", now));
        String sql = store.exportSql();
        assertTrue(sql.contains("INSERT INTO group_ai_usage"));
        try (var restored = java.sql.DriverManager.getConnection("jdbc:h2:mem:groupRestore")) {
            try (var statement = restored.createStatement()) {
                for (String part : sql.split(";")) if (!part.isBlank()) statement.execute(part);
                try (var rows = statement.executeQuery("SELECT SUM(call_count) FROM group_ai_usage")) {
                    assertTrue(rows.next()); assertEquals(3, rows.getInt(1));
                }
            }
        }
    }

    @Test void boundedContextIsIsolatedByUserAndCanBeCleared() {
        store.saveGroup(policy("300", "ALL", 20, 1));
        assertTrue(reply("300", "200", "第一句")); now = now.plusSeconds(31);
        assertTrue(reply("300", "200", "第二句")); now = now.plusSeconds(31);
        assertTrue(reply("300", "200", "第三句")); now = now.plusSeconds(31);
        assertTrue(reply("300", "201", "另一个用户")); now = now.plusSeconds(31);
        service.clearHistory("100", "300", "200");
        assertTrue(reply("300", "200", "清除后"));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> calls = ArgumentCaptor.forClass(List.class);
        verify(client, times(5)).chat(calls.capture(), anyString(), eq(false), eq(64));
        assertEquals(List.of(2, 4, 4, 2, 2), calls.getAllValues().stream().map(List::size).toList());
        assertEquals("第二句", calls.getAllValues().get(2).get(1).getString("content"));
    }

    @Test void legacyKeywordModeDoesNotGateCoreChatAndSwitchesStillApply() {
        store.saveGroup(policy("300", "KEYWORD", 5, 0));
        assertTrue(reply("300", "200", "我想吃什么"));
        now = now.plusSeconds(31);
        when(client.isConfigured()).thenReturn(false);
        assertFalse(reply("300", "200", "小杏"));
        when(client.isConfigured()).thenReturn(true);
        store.saveSettings(new Settings(false, true, true, true, true));
        assertFalse(reply("300", "200", "小杏"));
        store.saveSettings(new Settings(true, true, true, true, true));
        assertTrue(reply("300", "200", "小杏"));
    }

    @Test void failuresConsumeQuotaAndConcurrentCallsAreSkipped() {
        now = Instant.parse("2026-10-23T04:00:00Z");
        store.saveGroup(policy("300", "ALL", 1, 1));
        when(client.chat(anyList(), anyString(), eq(false), anyInt())).thenAnswer(call -> {
            now = now.plusSeconds(31);
            assertFalse(reply("300", "200", "并发消息"));
            throw new IllegalStateException("模拟失败");
        });
        assertFalse(reply("300", "200", "首次消息"));
        now = now.plusSeconds(31);
        assertFalse(reply("300", "200", "重试消息"));
        verify(client, times(1)).chat(anyList(), anyString(), anyBoolean(), anyInt());
    }
}
