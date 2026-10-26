package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.almond_eye_bot.config.BotConfiguration.Persona;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonaTests {
    @TempDir Path directory;
    BotConfigurationService store;
    @BeforeEach void setup() throws Exception { store = open(directory); }
    @AfterEach void close() throws Exception { store.close(); }
    BotConfigurationService open(Path root) throws Exception { return new BotConfigurationService(root.toString(), new OneBotProperties()); }
    String url(Path root) { return "jdbc:h2:file:" + root.resolve("data/bot-config").toString().replace('\\', '/'); }
    DeepSeekClient client() {
        var client = mock(DeepSeekClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.createMessage(anyString(), anyString())).thenAnswer(call ->
                JSONObject.of("role", call.getArgument(0), "content", call.getArgument(1)));
        return client;
    }

    @Test void validatesPersistsAndRestoresPromptWithQuotesAndNewlines() throws Exception {
        assertTrue(store.persona().prompt().isEmpty());
        assertThrows(ResponseStatusException.class, () -> store.savePersona(new Persona(null)));
        assertThrows(ResponseStatusException.class, () -> store.savePersona(new Persona("字".repeat(8001))));
        String prompt = "你叫小杏's；性格：俏皮。\n喜欢美食; 口癖：呀。";
        store.savePersona(new Persona(prompt));
        String sql = store.exportSql(); store.close(); store = open(directory);
        assertEquals(prompt, store.persona().prompt());
        Path restored = directory.resolve("restored"); Files.createDirectories(restored.resolve("data"));
        try (var db = DriverManager.getConnection(url(restored), "sa", ""); var statement = db.createStatement()) { statement.execute(sql); }
        var copy = open(restored);
        try { assertEquals(prompt, copy.persona().prompt()); } finally { copy.close(); }
        store.savePersona(new Persona("  \n "));
        assertTrue(store.persona().prompt().isEmpty()); assertFalse(store.systemPrompt(false).isBlank());
    }

    @Test void oldDatabaseGainsEmptyPersonaWithoutChangingExistingSettings() throws Exception {
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 10, 1)); store.close();
        try (var db = DriverManager.getConnection(url(directory), "sa", ""); var sql = db.createStatement()) { sql.execute("DROP TABLE bot_persona"); }
        store = open(directory);
        assertTrue(store.persona().prompt().isEmpty()); assertTrue(store.group("100", "300").enabled());
    }

    @Test void privateAndReasoningRequestsUseCurrentPersonaInExistingConversation() {
        var client = client();
        when(client.chat(anyList(), anyString(), eq(false))).thenReturn("收到");
        var ai = new AiChatService();
        ReflectionTestUtils.setField(ai, "deepSeekClient", client);
        ReflectionTestUtils.setField(ai, "configuration", store);
        ReflectionTestUtils.setField(ai, "memory", new ConversationMemoryService(store, client));
        store.savePersona(new Persona("你叫小杏，温柔，爱吃面。"));
        ai.chat("user", "你好");
        store.savePersona(new Persona("你叫阿杏，活泼，口癖是呀。"));
        ai.chat("user", "你叫什么");
        ai.chatWithReasoning("user", "想一想");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> requests = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).chat(requests.capture(), anyString(), eq(false));
        var calls = requests.getAllValues();
        assertTrue(calls.get(0).get(0).getString("content").startsWith("你叫小杏，温柔，爱吃面。"));
        for (int i = 1; i < 3; i++) {
            assertTrue(calls.get(i).get(0).getString("content").startsWith("你叫阿杏，活泼，口癖是呀。"));
            assertTrue(calls.get(i).get(0).getString("content").contains("不要模仿或延续旧回复的表达风格"));
            assertFalse(calls.get(i).stream().anyMatch(item -> "assistant".equals(item.getString("role"))));
            assertEquals(1, calls.get(i).stream().filter(item -> item.getString("role").equals("system")).count());
        }
        assertEquals("你好", calls.get(1).get(1).getString("content"));
        assertTrue(calls.get(1).get(2).getString("content").contains("历史机器人回复或摘要"));
        assertEquals(3, store.memories(ConversationMemoryService.owner("user")).size());
    }

    @Test void groupUsesCurrentPersonaKeepsBudgetAndDoesNotStoreOldSystemPromptInHistory() {
        var client = client();
        when(client.chat(anyList(), anyString(), eq(false), eq(64))).thenReturn("收到");
        var clock = mock(Clock.class);
        Instant first = Instant.parse("2026-10-23T01:00:00Z");
        when(clock.instant()).thenReturn(first);
        when(clock.millis()).thenReturn(first.toEpochMilli());
        var group = new GroupAutoReplyService(store, client, clock);
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 10, 1));
        store.saveGroupPersona("100","300",new Persona("性格温柔，爱好美食。"));
        assertTrue(group.reply("100", "300", "200", "你好", false, false, false).isPresent());
        store.saveGroupPersona("100","300",new Persona("性格俏皮，口癖是呀。"));
        when(clock.instant()).thenReturn(first.plusSeconds(31));
        when(clock.millis()).thenReturn(first.plusSeconds(31).toEpochMilli());
        assertTrue(group.reply("100", "300", "200", "你呢", false, false, false).isPresent());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> requests = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chat(requests.capture(), eq("deepseek-chat"), eq(false), eq(64));
        assertTrue(requests.getAllValues().get(0).get(0).getString("content").startsWith("性格温柔，爱好美食。"));
        var second = requests.getAllValues().get(1);
        assertTrue(second.get(0).getString("content").startsWith("性格俏皮，口癖是呀。"));
        assertEquals(1, second.stream().filter(item -> item.getString("role").equals("system")).count());
        assertEquals("你好", second.get(1).getString("content"));
        assertFalse(second.stream().anyMatch(item -> "assistant".equals(item.getString("role"))));
        assertTrue(second.get(2).getString("content").contains("历史机器人回复或摘要"));
    }

    @Test void personaChangeDuringPrivateGenerationDiscardsOldAnswerAndPreservesMemory() {
        var client = client();
        var memory = new ConversationMemoryService(store, client);
        String owner = ConversationMemoryService.owner("web:existing");
        var history = List.of(new org.almond_eye_bot.config.BotConfiguration.MemoryRecord(
                "我爱吃面", "旧人设的温柔回答", false));
        store.replaceMemories(owner, history);
        store.savePersona(new Persona("温柔"));
        when(client.chat(anyList(), anyString(), eq(false))).thenAnswer(call -> {
            store.savePersona(new Persona("简洁、冷静"));
            return "旧人设正在生成的回答";
        });
        var ai = new AiChatService();
        ReflectionTestUtils.setField(ai, "deepSeekClient", client);
        ReflectionTestUtils.setField(ai, "configuration", store);
        ReflectionTestUtils.setField(ai, "memory", memory);
        assertNull(ai.chat("web:existing", "继续"));
        assertEquals(history, store.memories(owner));
        when(client.chat(anyList(), anyString(), eq(false))).thenReturn("冷静的新回答");
        assertEquals("冷静的新回答", ai.chat("web:existing", "继续"));
        assertEquals(2, store.memories(owner).size());
    }

    @Test void personaChangeDuringGroupGenerationDiscardsOldAnswerWithoutResettingQuota() {
        var client = client();
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 10, 1));
        store.saveGroupPersona("100","300",new Persona("温柔"));
        when(client.chat(anyList(), anyString(), eq(false), eq(64))).thenAnswer(call -> {
            store.saveGroupPersona("100","300",new Persona("俏皮"));
            return "旧风格回答";
        });
        var group = new GroupAutoReplyService(store, client,
                Clock.systemUTC()); // callsToday() reads today's persisted usage, not a historical fixture day.
        assertTrue(group.reply("100", "300", "200", "你好", false, false, false).isEmpty());
        assertTrue(store.memories(ConversationMemoryService.groupOwner("100","300","200")).isEmpty());
        assertEquals(1, store.group("100", "300").callsToday());
    }

    @Test void summariesAreReferenceDataAndClearingPersonaRestoresCurrentDefault() {
        var original = List.of(JSONObject.of("role", "assistant", "content", "旧人设摘要"));
        var converted = PersonaContext.history(original);
        assertEquals("user", converted.get(0).getString("role"));
        assertTrue(converted.get(0).getString("content").contains("旧人设摘要"));
        assertEquals("assistant", original.get(0).getString("role"));
        store.savePersona(new Persona("旧名字"));
        store.savePersona(new Persona(""));
        assertTrue(store.systemPrompt(false).startsWith("你是一个友好、自然的聊天伙伴"));
        assertTrue(store.systemPrompt(false).contains("当前唯一有效的人设"));
    }
}
