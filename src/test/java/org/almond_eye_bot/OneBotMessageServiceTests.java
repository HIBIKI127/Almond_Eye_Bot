package org.almond_eye_bot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.config.BotConfiguration.Settings;
import org.almond_eye_bot.config.BotConfiguration.MatchedReply;
import java.util.Optional;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OneBotMessageServiceTests {
    private final AiChatService ai = mock(AiChatService.class);
    private final WikiService wiki = mock(WikiService.class);
    private final LocalImageService images = mock(LocalImageService.class);
    private final OneBotProperties properties = new OneBotProperties();
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private BotConfigurationService configuration;
    private final List<JSONObject> sent = new ArrayList<>();
    private OneBotMessageService service;
    private final GroupAutoReplyService groupReplies = mock(GroupAutoReplyService.class);

    @BeforeEach
    void setup() throws Exception {
        configuration = spy(new BotConfigurationService(directory.toString(), properties));
        // Explicitly opt into legacy actions for compatibility execution tests.
        for (var rule : configuration.rules()) configuration.saveRule(rule.id(),
                new org.almond_eye_bot.config.BotConfiguration.RuleInput(rule.keyword(), rule.matchType(),
                        rule.replyText(), rule.imageId(), true, rule.priority(), rule.action()));
        when(configuration.settings()).thenAnswer(invocation -> new Settings(true, true, true, true, properties.isGroupRequireAt()));
        doReturn(GroupAutoReplyTests.policy("300", "SMART", 10, 1)).when(configuration).observeGroup(eq("100"), eq("300"), any());
        when(configuration.group("100", "300")).thenReturn(GroupAutoReplyTests.policy("300", "SMART", 10, 1));
        service = new OneBotMessageService(configuration, new BotReplyService(ai, images, wiki, configuration, groupReplies), groupReplies);
    }

    @org.junit.jupiter.api.AfterEach void close() throws Exception { configuration.close(); }

    private JSONObject event(Object message, boolean group) {
        JSONObject event = JSONObject.of("self_id", 100L, "user_id", 200L, "group_id", 300L,
                "message_id", -1L, "message_type", group ? "group" : "private", "message", message);
        return event;
    }

    private void handle(JSONObject event) {
        service.handleEvent(event, (action, params) -> sent.add(JSONObject.of("action", action, "params", params)));
    }

    @Test
    void enabledGroupBypassesAtGateAndUsesBoundedReplyPath() {
        doReturn(GroupAutoReplyTests.policy("300", "SMART", 10, 1)).when(configuration).observeGroup(eq("100"), eq("300"), any());
        when(groupReplies.reply("100", "300", "200", "怎么做？", false, false, false)).thenReturn(Optional.of("这样做"));
        handle(event("怎么做？", true));
        assertEquals(1, segments(0).size());
        assertEquals("这样做", segments(0).getJSONObject(0).getJSONObject("data").getString("text"));
        verifyNoInteractions(ai);
    }

    @Test
    void disabledGroupDoesNotAutomaticallyCallAi() {
        doReturn(null).when(configuration).observeGroup(eq("100"), eq("300"), any());
        handle(event("怎么做？", true));
        verifyNoInteractions(groupReplies, ai);
        assertTrue(sent.isEmpty());
    }

    @Test
    void otherMentionIsReportedAndCustomRulesKeepPriority() throws Exception {
        doReturn(GroupAutoReplyTests.policy("300", "ALL", 10, 1)).when(configuration).observeGroup(eq("100"), eq("300"), any());
        handle(event("[CQ:at,qq=999] 怎么做？", true));
        verify(groupReplies).reply("100", "300", "200", "怎么做？", false, true, false);
        clearInvocations(groupReplies);
        when(configuration.match("你好")).thenReturn(Optional.of(new MatchedReply("自定义回复", null)));
        handle(event("你好", true));
        verifyNoInteractions(groupReplies);
    }

    @ParameterizedTest
    @ValueSource(strings={"\u00a0", "\u202f", "\u3000", "\u200b", "\ufeff", "\u2060", "\u00a0\u200b "})
    void atKeywordIgnoresQQEdgePaddingInArraysAndCq(String padding) throws Exception {
        // Exact rules are particularly sensitive to invisible characters added by clients.
        var rule = new org.almond_eye_bot.config.BotConfiguration.RuleInput("精准测试", "EXACT", "固定结果", null, true, 999, "REPLY");
        configuration.saveRule(null, rule);
        handle(event("[CQ:reply,id=5][CQ:at,qq=100]"+padding+"精准测试"+padding, true));
        handle(event(JSONArray.of(JSONObject.of("type","at","data",JSONObject.of("qq",100)),
                JSONObject.of("type","text","data",JSONObject.of("text",padding+"精准测试"+padding))),true));
        assertEquals(2,sent.size());
        for(int i=0;i<2;i++) assertEquals("固定结果",segments(i).getJSONObject(1).getJSONObject("data").getString("text"));
        verifyNoInteractions(ai,groupReplies);
    }
    @Test void removedMentionSeparatesArgumentsAndDoesNotCreateExactKeywords() throws Exception {
        when(groupReplies.reply("100","300","200","你好",true,false,true)).thenReturn(Optional.of("收到"));
        handle(event("AI[CQ:at,qq=100]你好",true));
        handle(event(JSONArray.of(JSONObject.of("type","text","data",JSONObject.of("text","AI")),
                JSONObject.of("type","at","data",JSONObject.of("qq","100")),
                JSONObject.of("type","text","data",JSONObject.of("text","你好"))),true));
        verify(groupReplies,times(2)).reply("100","300","200","你好",true,false,true);
        configuration.saveRule(null,new org.almond_eye_bot.config.BotConfiguration.RuleInput("foobar","EXACT","must-not-send",null,true,999,"REPLY"));
        handle(event("foo[CQ:at,qq=100]bar",true));
        verify(groupReplies).reply("100","300","200","foo bar",true,false,false);
        assertEquals(2,sent.size());
    }
    @ParameterizedTest
    @ValueSource(strings={"[CQ:at,qq=100]AI：你好", "[CQ:at,qq=100]AI: 你好", "[CQ:at,qq=100]AI\u00a0你好"})
    void mentionedParameterCommandsAcceptColonOrUnicodeSpace(String input) {
        when(groupReplies.reply("100","300","200","你好",true,false,true)).thenReturn(Optional.of("收到"));
        handle(event(input,true));
        verify(groupReplies).reply("100","300","200","你好",true,false,true);
        assertEquals(1,sent.size());
    }
    @Test void adjacentTextSegmentsRemainLiteralAndTextCannotForgeAt() throws Exception {
        configuration.saveRule(null,new org.almond_eye_bot.config.BotConfiguration.RuleInput("精准测试","EXACT","fixed",null,true,999,"REPLY"));
        handle(event(JSONArray.of(JSONObject.of("type","at","data",JSONObject.of("qq","100")),
                JSONObject.of("type","text","data",JSONObject.of("text","精准")),
                JSONObject.of("type","text","data",JSONObject.of("text","测试"))),true));
        assertEquals(1,sent.size());
        handle(event("&#91;CQ:at,qq=100&#93; 怎么做",true));
        verify(groupReplies).reply("100","300","200","[CQ:at,qq=100] 怎么做",false,false,false);
    }

    private JSONArray segments(int index) {
        return JSON.parseObject(sent.get(index).toJSONString()).getJSONObject("params").getJSONArray("message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"打灰", "今天一起打灰吧", "香草马娘", "随机小马", "帮助", "ping", "AI 你好", "wiki 光之战士", "清除历史", "自定义关键词", "[CQ:at,qq=100] 打灰", "/ping"})
    void unconfiguredAndDisabledGroupsBlockEveryFeature(String text) throws Exception {
        var disabled = new org.almond_eye_bot.config.BotConfiguration.GroupPolicy(
                "100", "300", "测试群", false, "SMART", "", 30, 50, 500, 256, 3, 60, 0);
        for (var policy : new org.almond_eye_bot.config.BotConfiguration.GroupPolicy[]{null, disabled}) {
            doReturn(policy).when(configuration).observeGroup(eq("100"), eq("300"), any());
            handle(event(text, true));
        }
        assertTrue(sent.isEmpty());
        verifyNoInteractions(ai, wiki, images, groupReplies);
        verify(configuration, never()).match(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"打灰", "今天还要继续打灰呀", "[CQ:reply,id=5] 一起来打灰"})
    void imageKeywordCanAppearInsideGroupSentences(String text) {
        when(images.getRandomDahuiImageBase64()).thenReturn("YWJj");
        handle(event(text, true));
        verify(images).getRandomDahuiImageBase64();
        assertEquals(1, sent.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI 帮助我解释打灰是什么意思", "请AI 帮助我解释打灰是什么意思", "AI　帮助我解释打灰是什么意思"})
    void argumentsKeepOtherKeywordsAndUnicodeSpace(String text) {
        handle(event(text, true));
        verify(groupReplies).reply("100", "300", "200", "帮助我解释打灰是什么意思", false, false, true);
        verifyNoInteractions(images, wiki);
    }

    @Test
    void disablingGroupDuringAiCallSuppressesItsPendingReply() {
        when(groupReplies.reply("100", "300", "200", "你好", false, false, true)).thenAnswer(call -> {
            when(configuration.group("100", "300")).thenReturn(null);
            return Optional.of("稍后才生成的结果");
        });
        handle(event("AI 你好", true));
        assertTrue(sent.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OpenAI 问题", "AI你好", "wiki光之战士", "shopping", "wikipedia 光之战士"})
    void parameterKeywordsRequireSeparatorsAndEnglishWordBoundaries(String text) {
        handle(event(text, false));
        assertTrue(sent.isEmpty());
        verify(ai).chat("onebot:100:private:200", text);
        verifyNoInteractions(wiki, images, groupReplies);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI 你好", "AI对话 你好", "ai 你好", "请AI 你好", "/AI 你好"})
    void aiAliasesAndLiteralOutput(String command) {
        when(ai.chat("onebot:100:private:200", "你好")).thenReturn("[CQ:image,file=secret]");
        handle(event(command, false));
        verify(ai).chat("onebot:100:private:200", "你好");
        assertEquals("send_private_msg", sent.get(0).getString("action"));
        assertEquals(1, segments(0).size());
        assertEquals(1, sent.size());
        assertEquals("text", segments(0).getJSONObject(0).getString("type"));
        assertEquals("[CQ:image,file=secret]", segments(0).getJSONObject(0).getJSONObject("data").getString("text"));
    }

    @Test
    void privateAiFailuresAndEmptyResultsAreSilent() {
        when(ai.chat("onebot:100:private:200", "你好")).thenReturn(null, " \n ")
                .thenThrow(new IllegalStateException("模拟 API 连接失败"));
        for (int i = 0; i < 3; i++) handle(event("AI 你好", false));
        assertTrue(sent.isEmpty());
    }

    @Test
    void groupAiExceptionsNeverFallBackToGenericErrorMessages() {
        when(groupReplies.reply("100", "300", "200", "你好", false, false, true))
                .thenThrow(new IllegalStateException("模拟 API 异常"));
        when(groupReplies.reply("100", "300", "200", "怎么做？", false, false, false))
                .thenThrow(new IllegalStateException("模拟自动回复异常"));
        handle(event("AI 你好", true));
        handle(event("怎么做？", true));
        assertTrue(sent.isEmpty());
    }

    @Test
    void groupKeywordsDoNotRequireAtAndUseBoundedReplyPath() {
        when(groupReplies.reply("100", "300", "200", "你好", false, false, true)).thenReturn(Optional.of("你好呀"));
        handle(event("AI 你好", true));
        verify(groupReplies).reply("100", "300", "200", "你好", false, false, true);
        JSONArray message = JSON.parseArray("[{\"type\":\"at\",\"data\":{\"qq\":\"100\"}},"
                + "{\"type\":\"text\",\"data\":{\"text\":\" AI 你好\"}}]");
        handle(event(message, true));
        verify(groupReplies).reply("100", "300", "200", "你好", true, false, true);
        verifyNoInteractions(ai);
        assertEquals("send_group_msg", sent.get(0).getString("action"));
        assertEquals(300L, sent.get(0).getJSONObject("params").getLongValue("group_id"));
    }

    @Test
    void cqAtParsingAndReset() {
        handle(event("[CQ:reply,id=1][CQ:at,qq=100] 请清除历史记录", true));
        verify(ai).clearHistory("onebot:100:group:300:200");
        verify(groupReplies).clearHistory("100", "300", "200");
        assertEquals(1, sent.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FF14Wiki 光之战士", "ff14 光之战士", "wiki 光之战士", "帮我查一下wiki 光之战士", "/wiki 光之战士"})
    void wikiAliasesDoNotTruncateQuery(String command) {
        var result = wiki.new WikiSearchResult();
        result.content = "查询结果";
        when(wiki.searchWiki("光之战士", null)).thenReturn(result);
        handle(event(command, false));
        verify(wiki).searchWiki("光之战士", null);
        assertEquals(1, sent.size());
    }

    @Test
    void emptyCommandsAndUnsupportedEvents() {
        handle(event("/ff14", false));
        handle(event("/AI", false));
        handle(event("/AIhello", false));
        JSONObject self = event("/AI 你好", false);
        self.put("user_id", 100L);
        handle(self);
        verify(ai).chat("onebot:100:private:200", "/AIhello");
        verifyNoMoreInteractions(ai);
        verifyNoInteractions(wiki);
        assertEquals(2, sent.size());
    }

    @Test
    void imageUsesPortableBase64WithoutAt() {
        when(images.getRandomDahuiImageBase64()).thenReturn("YWJj");
        handle(event("打灰", true));
        assertEquals("base64://YWJj", segments(0).getJSONObject(0).getJSONObject("data").getString("file"));
    }

    @Test
    void groupUsersDoNotShareHistory() {
        handle(event("AI 你好", true));
        JSONObject other = event("AI 你好", true);
        other.put("user_id", 201L);
        handle(other);
        verify(groupReplies).reply("100", "300", "200", "你好", false, false, true);
        verify(groupReplies).reply("100", "300", "201", "你好", false, false, true);
    }

    @Test
    void customRepliesTakePriorityAndSendBothTextAndImage() throws Exception {
        when(configuration.match("/AI 你好")).thenReturn(Optional.of(new MatchedReply("自定义文字", "YWJj")));
        handle(event("/AI 你好", false));
        verifyNoInteractions(ai, wiki, images);
        assertEquals(2, sent.size());
        assertEquals("自定义文字", segments(0).getJSONObject(0).getJSONObject("data").getString("text"));
        assertEquals("base64://YWJj", segments(1).getJSONObject(0).getJSONObject("data").getString("file"));
    }

    @Test
    void disabledFeaturesDoNotInvokeExternalServices() {
        when(configuration.settings()).thenReturn(new Settings(false, false, false, true, true));
        handle(event("/AI 你好", false));
        handle(event("/ff14 光之战士", false));
        handle(event("打灰", false));
        verifyNoInteractions(ai, wiki, images);
        assertEquals(0, sent.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ping", "[CQ:reply,id=5] ping", "[CQ:at,qq=999] ping", "[CQ:at,qq=all] ping"})
    void ordinaryMessagesAndMentionsOfOthersNeverQuote(String text) {
        for (boolean group : new boolean[]{true, false}) {
            sent.clear(); handle(event(text, group));
            assertEquals(1, sent.size()); assertEquals(1, segments(0).size());
            assertEquals("text", segments(0).getJSONObject(0).getString("type"));
            assertEquals("pong", segments(0).getJSONObject(0).getJSONObject("data").getString("text"));
        }
    }

    @Test void explicitBotMentionsQuoteInCqAndArrayFormats() {
        for (Object message : List.of("[CQ:at,qq=100] ping", JSONArray.of(
                JSONObject.of("type", "at", "data", JSONObject.of("qq", "100")),
                JSONObject.of("type", "text", "data", JSONObject.of("text", " ping"))))) {
            for (boolean group : new boolean[]{true, false}) {
                sent.clear(); handle(event(message, group));
                assertEquals(1, sent.size()); assertEquals(2, segments(0).size());
                assertEquals("reply", segments(0).getJSONObject(0).getString("type"));
                assertEquals("-1", segments(0).getJSONObject(0).getJSONObject("data").getString("id"));
                assertEquals("pong", segments(0).getJSONObject(1).getJSONObject("data").getString("text"));
            }
        }
    }

    @Test void mentionedAiReplyQuotesButMissingMessageIdFallsBackToPlainText() {
        when(groupReplies.reply("100", "300", "200", "怎么做？", true, false, false)).thenReturn(Optional.of("这样做"));
        handle(event("[CQ:at,qq=100] 怎么做？", true));
        assertEquals("reply", segments(0).getJSONObject(0).getString("type"));
        assertEquals("这样做", segments(0).getJSONObject(1).getJSONObject("data").getString("text"));
        for (String id : new String[]{null, ""}) {
            sent.clear(); var message = event("[CQ:at,qq=100] 怎么做？", true); message.put("message_id", id);
            handle(message); assertEquals(1, segments(0).size());
            assertEquals("text", segments(0).getJSONObject(0).getString("type"));
        }
    }

    @Test void sentenceRepliesQuoteOnlyTheFirstPartWhenMentioned() {
        when(groupReplies.reply("100", "300", "200", "怎么做？", true, false, false))
                .thenReturn(Optional.of("先坐下来。再聊一会儿！最后吃汉堡。"));
        handle(event("[CQ:at,qq=100] 怎么做？", true));
        assertEquals(3, sent.size()); assertEquals(2, segments(0).size());
        assertEquals("reply", segments(0).getJSONObject(0).getString("type"));
        assertEquals("先坐下来。", segments(0).getJSONObject(1).getJSONObject("data").getString("text"));
        assertEquals(1, segments(1).size()); assertEquals(1, segments(2).size());
        assertEquals("再聊一会儿！", segments(1).getJSONObject(0).getJSONObject("data").getString("text"));
        verify(groupReplies).reply("100", "300", "200", "怎么做？", true, false, false);
    }

    @Test void disablingGroupDuringDeliveryStopsLaterSentences() {
        when(groupReplies.reply("100", "300", "200", "怎么做？", false, false, false))
                .thenReturn(Optional.of("第一句。第二句。第三句。"));
        service.handleEvent(event("怎么做？", true), (action, params) -> {
            sent.add(JSONObject.of("action", action, "params", params));
            when(configuration.group("100", "300")).thenReturn(null);
        });
        assertEquals(1, sent.size());
    }

    @Test void failedSentenceDeliveryStopsWithoutErrorMessagesOrRetries() {
        when(ai.chat(anyString(), anyString())).thenReturn("第一句。第二句。第三句。");
        service.handleEvent(event("聊聊天", false), (action, params) -> {
            sent.add(JSONObject.of("action", action, "params", params));
            if (sent.size() == 2) throw new IllegalStateException("模拟发送失败");
        });
        assertEquals(2, sent.size());
        assertEquals("第一句。", segments(0).getJSONObject(0).getJSONObject("data").getString("text"));
        assertEquals("第二句。", segments(1).getJSONObject(0).getJSONObject("data").getString("text"));
        verify(ai, times(1)).chat(anyString(), anyString());
    }

    @Test void concurrentRepliesToSameGroupDoNotInterleaveTheirSentences() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondGenerated = new java.util.concurrent.CountDownLatch(1);
        when(groupReplies.reply(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(call -> {
                    if ("乙".equals(call.getArgument(3))) { secondGenerated.countDown(); return Optional.of("乙一。乙二。"); }
                    return Optional.of("甲一。甲二。");
                });
        var delivered = java.util.Collections.synchronizedList(new ArrayList<String>());
        java.util.function.BiConsumer<String, Map<String, Object>> sender = (action, params) -> {
            String content = JSONObject.from(params).getJSONArray("message").getJSONObject(0).getJSONObject("data").getString("text");
            delivered.add(content);
            if ("甲一。".equals(content)) {
                entered.countDown();
                try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
            }
        };
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.handleEvent(event("甲", true), sender));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var other = event("乙", true); other.put("user_id", "201");
            var second = pool.submit(() -> service.handleEvent(other, sender));
            assertTrue(secondGenerated.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("甲一。"), delivered);
            release.countDown(); first.get(5, java.util.concurrent.TimeUnit.SECONDS); second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of("甲一。", "甲二。", "乙一。", "乙二。"), delivered);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void imageReplyQuotesOnlyWhenBotIsMentioned() {
        when(images.getRandomDahuiImageBase64()).thenReturn("YWJj");
        handle(event("打灰", true)); handle(event("[CQ:at,qq=100] 打灰", true));
        assertEquals(1, segments(0).size()); assertEquals("image", segments(0).getJSONObject(0).getString("type"));
        assertEquals(2, segments(1).size()); assertEquals("reply", segments(1).getJSONObject(0).getString("type"));
        assertEquals("image", segments(1).getJSONObject(1).getString("type"));
    }
}
