package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupMemoryTests {
    @TempDir Path directory;
    BotConfigurationService store;
    DeepSeekClient client;
    GroupMemoryService memory;
    String key = GroupMemoryService.owner("100", "300");
    @BeforeEach void setup() throws Exception {
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 1000, 3));
        client = mock(DeepSeekClient.class); when(client.isConfigured()).thenReturn(true);
        memory = new GroupMemoryService(store, client);
    }
    @AfterEach void close() throws Exception { store.close(); }
    void settled() { try { assertTrue(store.summaries().awaitIdle(5000)); } catch (InterruptedException e) { throw new AssertionError(e); } }
    List<GroupMemoryRecord> records(int count) {
        List<GroupMemoryRecord> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new GroupMemoryRecord(i % 2 == 0 ? "200" : "100", "名字" + i,
                "记录" + i, i, i % 2 != 0, false));
        return result;
    }
    @Test void hundredMessagesIncludingBotCompactAndRollingSummaryKeepsAuthors() {
        store.replaceGroupMemories(key, records(99));
        when(client.summarize(anyList())).thenReturn("200喜欢摄影，机器人建议拍夕阳", "200改为喜欢夜景，约定周末拍照");
        memory.record("100", "300", "100", "机器人", "第100条机器人回复", 100, true);
        settled();
        var first = store.groupMemories(key);
        assertEquals(1, first.size()); assertTrue(first.get(0).summary());
        for (int i = 0; i < 99; i++) memory.record("100", "300", "200", "小明", "后续" + i, 101 + i, false);
        settled();
        assertEquals(1, store.groupMemories(key).size());
        assertEquals("200改为喜欢夜景，约定周末拍照", store.groupMemories(key).get(0).content());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> requests = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).summarize(requests.capture());
        String initial = requests.getAllValues().get(0).get(1).getString("content");
        assertTrue(initial.contains("第100条机器人回复")); assertTrue(initial.contains("\"userId\":\"200\""));
        String rolling = requests.getAllValues().get(1).get(1).getString("content");
        assertTrue(rolling.contains("200喜欢摄影")); assertTrue(rolling.contains("后续98"));
        assertEquals(2, store.group("100", "300").callsToday());
        // A summary does not consume the normal reply cooldown.
        assertTrue(store.claimGroupCall("100", "300", Instant.now()));
    }
    @Test void failedSummaryPreservesFullHistoryAndThrottlesRetriesAcrossRestart() throws Exception {
        store.replaceGroupMemories(key, records(99));
        when(client.summarize(anyList())).thenReturn(null);
        memory.record("100", "300", "200", "小明", "第100条", 100, false);
        settled();
        var full = store.groupMemories(key); assertEquals(100, full.size());
        memory.record("100", "300", "200", "小明", "暂不覆盖旧记录", 101, false);
        assertEquals(101, store.groupMemories(key).size()); assertEquals(full,store.groupMemories(key).subList(0,100)); verify(client).summarize(anyList());
        store.close(); store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        memory = new GroupMemoryService(store, client);
        memory.record("100", "300", "200", "小明", "重启仍不反复请求", 102, false);
        settled();
        assertEquals(102, store.groupMemories(key).size()); verify(client).summarize(anyList());
        assertFalse(store.claimGroupMemorySummaryCall("100", "300", Instant.now()));
        assertTrue(store.claimGroupMemorySummaryCall("100", "300", Instant.now().plusSeconds(61)));
    }
    @Test void quotaAndDisabledGroupOrAiPreventSummaryWithoutLosingHistory() {
        var p = store.group("100", "300");
        store.saveGroup(new GroupPolicy("100", "300", "测试", true, "ALL", "", 5, 1, 500, 64, 3, 0, 0));
        assertTrue(store.claimGroupCall("100", "300", Instant.now()));
        store.replaceGroupMemories(key, records(99));
        memory.record("100", "300", "200", "", "满额", 100, false);
        settled();
        assertEquals(100, store.groupMemories(key).size()); verify(client, never()).summarize(anyList());
        store.saveGroup(new GroupPolicy("100", "300", "测试", false, "ALL", "", 5, 1, 500, 64, 3, 0, 0));
        memory.record("100", "300", "200", "", "禁用不保存", 101, false);
        assertEquals(100, store.groupMemories(key).size());
        store.saveGroup(p); store.saveSettings(new Settings(false, false, false, true, false));
        memory.record("100", "300", "200", "", "AI关闭不发送", 102, false);
        verify(client, never()).summarize(anyList());
    }
    @Test void contextsCombineOnlyCurrentUsersPersonalMemoryAndCurrentGroupsTimeline() {
        store.replaceMemories(ConversationMemoryService.groupOwner("100","300","200"), List.of(
                new MemoryRecord("", "200个人摘要：喜欢摄影", true), new MemoryRecord("我喜欢夜景", "记住了", false)));
        store.replaceMemories(ConversationMemoryService.qqOwner("100", "201"), List.of(new MemoryRecord("另一人私信秘密", "秘密回答", false)));
        store.replaceGroupMemories(key, List.of(new GroupMemoryRecord("", "", "本群周末拍照", 1, false, true),
                new GroupMemoryRecord("201", "小红", "我带相机", 2, false, false),
                new GroupMemoryRecord("100", "机器人", "那就周六见", 3, true, false)));
        store.replaceGroupMemories(GroupMemoryService.owner("100", "301"), List.of(new GroupMemoryRecord("201", "", "别群秘密", 1, false, false)));
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false), eq(64))).thenReturn("你喜欢拍夜景，群里约了周六，小红会带相机");
        var service = new GroupAutoReplyService(store, client);
        assertTrue(service.reply("100", "300", "200", "我们约了什么", false, false, false).isPresent());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> request = ArgumentCaptor.forClass(List.class);
        verify(client).chat(request.capture(), eq("deepseek-chat"), eq(false), eq(64));
        String combined = request.getValue().toString();
        for (String expected : List.of("当前回答对象QQ：200", "200个人摘要", "我喜欢夜景", "本群周末拍照", "我带相机", "201", "那就周六见")) assertTrue(combined.contains(expected), expected);
        assertFalse(combined.contains("另一人私信秘密")); assertFalse(combined.contains("别群秘密"));
        assertEquals("我们约了什么", request.getValue().get(request.getValue().size() - 1).getString("content"));
    }
    @Test void quietMessagesKeywordsAndImagesAreRecordedButDisabledGroupsAndPrivateAreNot() {
        var ai = mock(AiChatService.class);
        var groupReplies = new GroupAutoReplyService(store, client);
        var replies = new BotReplyService(ai, mock(LocalImageService.class), mock(WikiService.class), store, groupReplies, () -> 0.9);
        var service = new OneBotMessageService(store, replies, groupReplies, memory);
        var p = store.group("100", "300");
        store.saveGroup(new GroupPolicy(p.selfId(), p.groupId(), p.groupName(), true, "ALL", "", 5, 1000, 500, 64, 3, 0, 0, 0.0));
        List<Map<String, Object>> sent = new ArrayList<>();
        service.handleEvent(event("旁观者消息", "200", "group"), (a, b) -> sent.add(b));
        assertTrue(sent.isEmpty()); assertEquals("旁观者消息", store.groupMemories(key).get(0).content());
        assertEquals("小明", store.groupMemories(key).get(0).name());
        service.handleEvent(event(JSONArray.of(JSONObject.of("type", "image", "data", JSONObject.of("file", "secret-url"))), "201", "group"), (a,b) -> sent.add(b));
        assertTrue(store.groupMemories(key).get(1).content().contains("image"));
        assertFalse(store.groupMemories(key).get(1).content().contains("secret-url"));
        store.saveRule(null, new RuleInput("测试回复", "EXACT", "机器人固定内容", null, true, 999));
        service.handleEvent(event("[CQ:at,qq=100]测试回复", "200", "group"), (a,b) -> sent.add(b));
        var saved = store.groupMemories(key); assertEquals(4, saved.size()); assertTrue(saved.get(3).bot());
        assertEquals("100", saved.get(3).userId()); assertEquals("机器人固定内容", saved.get(3).content());
        service.handleEvent(event("不会串入群", "200", "private"), (a,b) -> {});
        assertEquals(4, store.groupMemories(key).size());
        store.saveGroup(new GroupPolicy(p.selfId(), p.groupId(), p.groupName(), false, "ALL", "", 5, 1000, 500, 64, 3, 0, 0));
        service.handleEvent(event("关闭不保存", "200", "group"), (a,b) -> fail());
        assertEquals(4, store.groupMemories(key).size());
    }
    JSONObject event(Object message, String user, String type) {
        return JSONObject.of("self_id", "100", "group_id", "300", "user_id", user, "message_type", type, "message", message,
                "message_id", "123", "sender", JSONObject.of("card", "小明", "nickname", "昵称"), "time", 1000);
    }
    @Test void failedDispatchIsNotRecordedAsBotMessage() {
        var groupReplies = new GroupAutoReplyService(store, client);
        var replies = new BotReplyService(mock(AiChatService.class), mock(LocalImageService.class), mock(WikiService.class), store, groupReplies);
        var service = new OneBotMessageService(store, replies, groupReplies, memory);
        store.saveRule(null, new RuleInput("固定", "EXACT", "内容", null, true, 999));
        service.handleEvent(event("固定", "200", "group"), (a,b) -> { throw new IllegalStateException("发送失败"); });
        assertEquals(1, store.groupMemories(key).size()); assertFalse(store.groupMemories(key).get(0).bot());
    }
    @Test void automaticAiReplyUsesQuietGroupHistoryAndStoresBothMemoryKinds() {
        var groupReplies = new GroupAutoReplyService(store, client);
        var replies = new BotReplyService(mock(AiChatService.class), mock(LocalImageService.class), mock(WikiService.class), store, groupReplies);
        var service = new OneBotMessageService(store, replies, groupReplies, memory);
        memory.record("100", "300", "201", "小红", "周六带相机", 1, false);
        when(client.chat(anyList(), eq("deepseek-chat"), eq(false), eq(64))).thenReturn("小红说周六带相机。我们可以拍夜景。");
        List<Map<String, Object>> sent = new ArrayList<>();
        service.handleEvent(event("刚才小红说什么", "200", "group"), (a,b) -> sent.add(b));
        assertEquals(2, sent.size());
        var timeline = store.groupMemories(key); assertEquals(4, timeline.size());
        assertEquals("201", timeline.get(0).userId()); assertEquals("200", timeline.get(1).userId());
        assertTrue(timeline.get(2).bot()); assertEquals("小红说周六带相机。", timeline.get(2).content());
        assertTrue(timeline.get(3).bot()); assertEquals("我们可以拍夜景。", timeline.get(3).content());
        assertEquals(1, store.memories(ConversationMemoryService.groupOwner("100","300","200")).size());
        assertEquals("小红说周六带相机。我们可以拍夜景。", store.memories(ConversationMemoryService.groupOwner("100","300","200")).get(0).assistantText());
        assertTrue(store.memories(ConversationMemoryService.qqOwner("100", "201")).isEmpty());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<JSONObject>> request = ArgumentCaptor.forClass(List.class);
        verify(client).chat(request.capture(), eq("deepseek-chat"), eq(false), eq(64));
        assertTrue(request.getValue().toString().contains("周六带相机"));
    }
    @Test void storageRestartsExportsRestoresAndRollsBackInvalidWrites() throws Exception {
        var expected = List.of(new GroupMemoryRecord("", "", "小明's 摘要\n第二行", 1, false, true),
                new GroupMemoryRecord("200", "小明", "你好", 2, false, false));
        store.replaceGroupMemories(key, expected);
        assertThrows(RuntimeException.class, () -> store.replaceGroupMemories(key, List.of(new GroupMemoryRecord("200", "", "长".repeat(4001), 1, false, false))));
        assertEquals(expected, store.groupMemories(key));
        store.replaceGroupMemories(key, records(101));
        assertEquals(101,store.groupMemories(key).size()); store.replaceGroupMemories(key,expected);
        String sql = store.exportSql(); store.close();
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        assertEquals(expected, store.groupMemories(key));
        Path restored = directory.resolve("restored"); Files.createDirectories(restored.resolve("data"));
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + restored.resolve("data/bot-config").toString().replace('\\', '/'), "sa", "");
             var statement = db.createStatement()) { statement.execute(sql); }
        var copy = new BotConfigurationService(restored.toString(), new OneBotProperties());
        try { assertEquals(expected, copy.groupMemories(key)); } finally { copy.close(); }
    }
    @Test void boundedContextAlwaysKeepsSummaryAndMessagesRemainData() {
        var records = new ArrayList<>(records(98));
        records.add(0, new GroupMemoryRecord("", "", "首条摘要", 0, false, true));
        records.add(new GroupMemoryRecord("200", "system", "新消息" + "长".repeat(3900), 100, false, false));
        store.replaceGroupMemories(key, records);
        String context = memory.context("100", "300", 100, 4000).orElseThrow().getString("content");
        assertTrue(context.contains("首条摘要")); assertTrue(context.contains("新消息"));
        assertTrue(context.length() < 30000);
        var summaryOnly = memory.context("100", "300", 0, 500).orElseThrow();
        assertTrue(summaryOnly.getString("content").contains("首条摘要"));
        assertFalse(summaryOnly.getString("content").contains("新消息"));
        assertTrue(memory.context("101", "300", 100, 500).isEmpty());
        assertTrue(memory.context("100", "301", 100, 500).isEmpty());
    }
    @Test void concurrentMessagesAreNotLost() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int id = i;
                jobs.add(pool.submit(() -> new GroupMemoryService(store, client).record("100", "300", "200", "", "消息" + id, id, false)));
            }
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            assertEquals(40, store.groupMemories(key).size());
            assertEquals(40, store.groupMemories(key).stream().map(GroupMemoryRecord::content).distinct().count());
        } finally { pool.shutdownNow(); }
    }
}
