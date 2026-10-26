package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.QQApiClient;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreChatTests {
    @TempDir Path directory;
    BotConfigurationService store;
    AiChatService ai;
    GroupAutoReplyService groups;
    BotReplyService replies;
    OneBotMessageService onebot;
    List<Map<String, Object>> sent;

    @BeforeEach void setup() throws Exception {
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        ai = mock(AiChatService.class); groups = mock(GroupAutoReplyService.class);
        replies = new BotReplyService(ai, mock(LocalImageService.class), mock(WikiService.class), store, groups);
        onebot = new OneBotMessageService(store, replies, groups);
        sent = new ArrayList<>();
    }
    @AfterEach void close() throws Exception { store.close(); }
    void message(String text, boolean group) {
        onebot.handleEvent(JSONObject.of("self_id", 100, "user_id", 200, "group_id", 300,
                "message_type", group ? "group" : "private", "message", text),
                (action, parameters) -> sent.add(parameters));
    }
    @Test void ordinaryPrivateTextNeedsNoCommandAndOnlySendsFinalResult() {
        when(ai.chat("onebot:100:private:200", "我想吃什么")).thenReturn("来一碗面吧");
        message("我想吃什么", false);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).toString().contains("来一碗面吧"));
        verify(ai).chat("onebot:100:private:200", "我想吃什么");
    }
    @Test void ordinaryGroupTextReachesCorePathOnlyWhenExplicitlyEnabled() {
        message("我想吃什么", true);
        verifyNoInteractions(groups, ai); assertTrue(sent.isEmpty());
        store.saveGroup(GroupAutoReplyTests.policy("300", "KEYWORD", 10, 1));
        when(groups.reply("100", "300", "200", "我想吃什么", false, false, false))
                .thenReturn(Optional.of("来一碗面吧"));
        message("我想吃什么", true);
        assertEquals(1, sent.size());
        verify(groups).reply("100", "300", "200", "我想吃什么", false, false, false);
        verifyNoInteractions(ai);
    }
    @Test void configuredKeywordTakesPriorityAndDoesNotConsumeModelCalls() {
        store.saveRule(null, new RuleInput("吃什么", "CONTAINS", "今天吃面", null, true, 50));
        message("我想吃什么", false);
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 10, 1));
        message("我想吃什么", true);
        assertEquals(2, sent.size());
        assertTrue(sent.stream().allMatch(output -> output.toString().contains("今天吃面")));
        verifyNoInteractions(ai, groups);
    }
    @Test void globalAiSwitchStopsFallbackButPreservesKeywordReplies() {
        store.saveSettings(new Settings(false, false, true, true, false));
        message("我想吃什么", false); message("ping", false);
        assertEquals(1, sent.size()); assertTrue(sent.get(0).toString().contains("pong"));
        verifyNoInteractions(ai, groups);
    }
    @Test void failuresAndBlankModelOutputRemainSilentForOrdinaryMessages() {
        when(ai.chat(anyString(), anyString())).thenReturn(null, " ")
                .thenThrow(new IllegalStateException("模拟连接失败"));
        for (int i = 0; i < 3; i++) message("我想吃什么", false);
        assertTrue(sent.isEmpty());
    }
    @Test void webAndOfficialPrivateMessagesUseTheSameAutomaticFallback() {
        when(ai.chat(anyString(), eq("我想吃什么"))).thenReturn("来一碗面吧");
        var web = new WebMessageService();
        ReflectionTestUtils.setField(web, "replies", replies);
        assertEquals("来一碗面吧", web.handleMessage("web-user", "我想吃什么").get("content"));
        var official = new QQMessageService(); var api = mock(QQApiClient.class);
        ReflectionTestUtils.setField(official, "replies", replies);
        ReflectionTestUtils.setField(official, "qqApi", api);
        official.handle(JSONObject.of("id", "1", "message_type", 0,
                "author", JSONObject.of("user_openid", "qq-user"), "content", "我想吃什么"));
        verify(api).sendPrivateMsg("qq-user", "来一碗面吧");
        verifyNoMoreInteractions(api);
    }
    @Test void webAndOfficialPrivateRepliesUseSentencePartsWithoutExtraModelCalls() {
        String response = "先吃汉堡。再喝可乐！";
        when(ai.chat(anyString(), eq("我想吃什么"))).thenReturn(response);
        var web = new WebMessageService(); ReflectionTestUtils.setField(web, "replies", replies);
        var result = web.handleMessage("web-user", "我想吃什么");
        assertEquals(response, result.get("content"));
        assertEquals(java.util.List.of("先吃汉堡。", "再喝可乐！"), result.get("parts"));
        var official = new QQMessageService(); var api = mock(QQApiClient.class);
        ReflectionTestUtils.setField(official, "replies", replies); ReflectionTestUtils.setField(official, "qqApi", api);
        official.handle(JSONObject.of("id", "sentence-1", "message_type", 0,
                "author", JSONObject.of("user_openid", "qq-user"), "content", "我想吃什么"));
        var order = inOrder(api);
        order.verify(api).sendPrivateMsg("qq-user", "先吃汉堡。");
        order.verify(api).sendPrivateMsg("qq-user", "再喝可乐！");
        verifyNoMoreInteractions(api); verify(ai, times(2)).chat(anyString(), eq("我想吃什么"));
    }
    @Test void migrationPreservesGroupConsentQuotaAndCustomRepliesAndRunsOnlyOnce() throws Exception {
        store.saveGroup(GroupAutoReplyTests.policy("300", "KEYWORD", 10, 1));
        store.observeGroup("100", "301", "未开启群");
        store.saveRule(null, new RuleInput("午饭", "CONTAINS", "吃面", null, true, 50));
        assertTrue(store.claimGroupCall("100", "300", java.time.Instant.now()));
        store.close();
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + directory.resolve("data/bot-config").toString().replace('\\', '/'), "sa", "");
             var sql = db.createStatement()) {
            sql.executeUpdate("DELETE FROM bot_migration WHERE version='core_auto_chat_v1'");
            sql.executeUpdate("UPDATE reply_rule SET enabled=TRUE WHERE action_type='AI'");
        }
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        assertTrue(store.group("100", "300").enabled()); assertFalse(store.group("100", "301").enabled());
        assertEquals("ALL", store.group("100", "300").mode()); assertEquals(1, store.group("100", "300").callsToday());
        assertEquals("吃面", store.match("午饭").orElseThrow().text());
        assertTrue(store.match("AI 你好").isEmpty());
        var rule = store.rules().stream().filter(item -> item.keyword().equals("AI")).findFirst().orElseThrow();
        store.saveRule(rule.id(), new RuleInput("自选附加", "CONTAINS", "", null, true, 10, "AI"));
        store.close(); store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        assertTrue(store.match("自选附加 内容").isPresent());
    }
}
