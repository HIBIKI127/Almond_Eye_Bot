package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.almond_eye_bot.config.BotConfiguration.MemoryRecord;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationMemoryTests {
    @TempDir Path directory;
    BotConfigurationService store;
    DeepSeekClient client;
    ConversationMemoryService memory;
    final String owner = ConversationMemoryService.qqOwner("100", "200");
    @BeforeEach void setup() throws Exception {
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        client = mock(DeepSeekClient.class); when(client.isConfigured()).thenReturn(true);
        memory = new ConversationMemoryService(store, client);
    }
    @AfterEach void close() throws Exception { store.close(); }
    List<MemoryRecord> turns(int count) {
        List<MemoryRecord> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new MemoryRecord("用户第" + i + "句", "回复第" + i + "句", false));
        return result;
    }
    void settled() { try { assertTrue(store.summaries().awaitIdle(5000)); } catch (InterruptedException e) { throw new AssertionError(e); } }
    AiChatService ai() {
        var ai = new AiChatService();
        ReflectionTestUtils.setField(ai, "deepSeekClient", client);
        ReflectionTestUtils.setField(ai, "configuration", store);
        ReflectionTestUtils.setField(ai, "memory", memory);
        return ai;
    }

    @Test void fiftiethTurnCompactsToFirstRecordAndLaterCompactionIncludesOldSummary() {
        store.replaceMemories(owner, turns(49));
        when(client.summarize(anyList())).thenReturn("第一版长期摘要", "第二版长期摘要");
        memory.remember(owner, "用户第49句", "回复第49句", () -> true);
        settled();
        assertEquals(List.of(new MemoryRecord("", "第一版长期摘要", true)), store.memories(owner));
        for (int i = 0; i < 49; i++) memory.remember(owner, "新问题" + i, "新回复" + i, () -> true);
        settled();
        assertEquals(List.of(new MemoryRecord("", "第二版长期摘要", true)), store.memories(owner));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> requests = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).summarize(requests.capture());
        String first = requests.getAllValues().get(0).get(1).getString("content");
        assertTrue(first.contains("用户第0句")); assertTrue(first.contains("回复第49句"));
        String second = requests.getAllValues().get(1).get(1).getString("content");
        assertTrue(second.contains("第一版长期摘要")); assertTrue(second.contains("新回复48"));
        assertTrue(memory.context(owner, 0, 500, 500).get(0).getString("content").contains("第二版长期摘要"));
        memory.remember(owner, "继续聊", "继续答", () -> true);
        assertTrue(store.memories(owner).get(0).summary()); assertEquals(2, store.memories(owner).size());
    }

    @Test void failedSummaryRetainsOldTurnsAndAllowsNewAnswersAndRestartRecovery() throws Exception {
        store.replaceMemories(owner, turns(49));
        when(client.summarize(anyList()))
                .thenThrow(new IllegalStateException("模拟连接失败")).thenReturn("归纳成功");
        memory.remember(owner, "最后问题", "最后回答", () -> true);
        settled();
        List<MemoryRecord> original = store.memories(owner);
        assertEquals(50, original.size());
        assertTrue(memory.prepare(owner, () -> true)); assertEquals(original, store.memories(owner));
        memory.remember(owner,"继续提问","正常回复",() -> true);
        assertEquals(51,memory.size(owner));
        store.close(); store = new BotConfigurationService(directory.toString(),new OneBotProperties());
        memory = new ConversationMemoryService(store,client);
        memory.remember(owner,"重启后提问","正常回复",() -> true); settled();
        assertEquals(3, memory.size(owner));
        assertTrue(store.memories(owner).get(0).summary());
    }

    @Test void disabledSummaryPermitDoesNotInvokeModelOrDeleteMemory() {
        store.replaceMemories(owner, turns(50));
        assertTrue(memory.prepare(owner, () -> false));
        assertEquals(50, memory.size(owner)); verifyNoInteractions(client);
    }

    @Test void sameQqHasSeparateGroupAndPrivateMemoryWhileOtherUsersAndBotAccountsStaySeparate() {
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false))).thenReturn("私信回复");
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false), eq(64))).thenReturn("群聊回复");
        assertEquals("私信回复", ai().chat("onebot:100:private:200", "我爱吃面"));
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 10, 1));
        var group = new GroupAutoReplyService(store, client, Clock.fixed(Instant.parse("2026-10-23T01:00:00Z"), ZoneOffset.UTC));
        assertTrue(group.reply("100", "300", "200", "我喜欢什么", false, false, false).isPresent());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> requests = ArgumentCaptor.forClass(List.class);
        verify(client).chat(requests.capture(), eq("deepseek-chat"), eq(false), eq(64));
        assertFalse(requests.getValue().toString().contains("我爱吃面"));
        assertEquals(1, memory.size(owner));
        String groupOwner=ConversationMemoryService.groupOwner("100","300","200");
        assertEquals(1,memory.size(groupOwner));
        assertNotEquals(groupOwner, ConversationMemoryService.owner("onebot:100:group:301:200"));
        assertEquals(0, memory.size(ConversationMemoryService.qqOwner("100", "201")));
        assertEquals(0, memory.size(ConversationMemoryService.qqOwner("101", "200")));
        assertNotEquals(owner, ConversationMemoryService.owner("web:onebot:100:private:200"));
        assertEquals("私信回复", ai().chat("onebot:100:private:200", "继续"));
        assertEquals(2, memory.size(owner));
        group.clearHistory("100", "300", "200"); assertEquals(0, memory.size(groupOwner));assertEquals(2,memory.size(owner));
    }

    @Test void memorySurvivesRestartAndSqlRestoreAndFailedWritesRollback() throws Exception {
        var records = List.of(new MemoryRecord("", "用户喜欢面; 小杏's 摘要", true), new MemoryRecord("新问题\n第二行", "新答案", false));
        store.replaceMemories(owner, records);
        assertThrows(RuntimeException.class, () -> store.replaceMemories(owner, List.of(new MemoryRecord("长".repeat(4001), "失败", false))));
        assertEquals(records, store.memories(owner));
        String backup = store.exportSql(); store.close();
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        assertEquals(records, store.memories(owner));
        Path restored = directory.resolve("restored"); Files.createDirectories(restored.resolve("data"));
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + restored.resolve("data/bot-config").toString().replace('\\', '/'), "sa", "");
             var statement = db.createStatement()) { statement.execute(backup); }
        var copy = new BotConfigurationService(restored.toString(), new OneBotProperties());
        try { assertEquals(records, copy.memories(owner)); } finally { copy.close(); }
    }

    @Test void groupSummaryConsumesAdditionalDailyQuotaAndKeepsOriginalReply() {
        String owner=ConversationMemoryService.groupOwner("100","300","200");
        store.replaceMemories(owner, turns(49));
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 2, 1));
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false), eq(64))).thenReturn("正常回答");
        when(client.summarize(anyList())).thenReturn("摘要");
        var group = new GroupAutoReplyService(store, client);
        assertEquals("正常回答", group.reply("100", "300", "200", "第50轮", false, false, false).orElseThrow());
        settled();
        assertEquals(2, store.group("100", "300").callsToday());
        assertEquals(1, memory.size(owner));
    }

    @Test void failedPrivateReplyDoesNotSaveANewTurn() {
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false))).thenReturn(null, " ")
                .thenThrow(new IllegalStateException("模拟失败"));
        var ai = ai();
        for (int i = 0; i < 3; i++) assertNull(ai.chat("onebot:100:private:200", "你好"));
        assertEquals(0, memory.size(owner));
    }

    @Test void concurrentGroupAndPrivateCallsUseSeparateLocksAndKeepSeparateTurns() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 10, 1));
        var group = new GroupAutoReplyService(store, client);
        var ai = ai();
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false), eq(64))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return "群聊回答";
        });
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false))).thenReturn("私信回答");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> group.reply("100", "300", "200", "群聊先问", false, false, false));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second = executor.submit(() -> ai.chat("onebot:100:private:200", "私信再问"));
            assertEquals("私信回答",second.get(2,java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            assertEquals("群聊回答", first.get(5, java.util.concurrent.TimeUnit.SECONDS).orElseThrow());
            assertEquals("私信回答", second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            var records = store.memories(owner);
            assertEquals(1, records.size());
            assertEquals("群聊先问",store.memories(ConversationMemoryService.groupOwner("100","300","200")).get(0).userText());
            assertEquals("私信再问", records.get(0).userText());
            @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> messages = ArgumentCaptor.forClass(List.class);
            verify(client).chat(messages.capture(), eq("deepseek-chat"), eq(false));
            assertFalse(messages.getValue().toString().contains("群聊先问"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void noQuotaForSummaryKeepsFullMemoryAndNeverCallsSummaryApi() {
        String owner=ConversationMemoryService.groupOwner("100","300","200");
        store.replaceMemories(owner, turns(49));
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 1, 1));
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false), eq(64))).thenReturn("第50轮回答");
        var group = new GroupAutoReplyService(store, client);
        assertEquals("第50轮回答", group.reply("100", "300", "200", "第50轮问题", false, false, false).orElseThrow());
        settled();
        assertEquals(50, memory.size(owner)); verify(client, never()).summarize(anyList());
    }
}
