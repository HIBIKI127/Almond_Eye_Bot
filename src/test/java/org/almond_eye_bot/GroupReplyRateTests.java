package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.config.BotConfiguration.GroupPolicy;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.function.DoubleSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupReplyRateTests {
    @TempDir Path directory;
    BotConfigurationService store;
    AiChatService ai;
    GroupAutoReplyService groups;
    DoubleSupplier random;
    OneBotMessageService service;
    List<Map<String, Object>> sent;

    GroupPolicy policy(double rate, boolean enabled) {
        return new GroupPolicy("100", "300", "测试群", enabled, "ALL", "", 30, 50, 500, 256, 3, 0, 0, rate);
    }
    @BeforeEach void setup() throws Exception {
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        ai = mock(AiChatService.class); groups = mock(GroupAutoReplyService.class); random = mock(DoubleSupplier.class);
        var replies = new BotReplyService(ai, mock(LocalImageService.class), mock(WikiService.class), store, groups, random);
        service = new OneBotMessageService(store, replies, groups); sent = new ArrayList<>();
        when(groups.reply(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(Optional.of("模型回复"));
        when(ai.chat(anyString(), anyString())).thenReturn("私信回复");
    }
    @AfterEach void close() throws Exception { store.close(); }
    void message(String text, boolean group) {
        service.handleEvent(JSONObject.of("self_id", 100, "user_id", 200, "group_id", 300,
                "message_type", group ? "group" : "private", "message", text),
                (action, parameters) -> sent.add(parameters));
    }
    @Test void zeroSuppressesBothKeywordsAndModelWithoutConsumingQuota() {
        store.saveGroup(policy(0, true));
        message("ping", true); message("我想吃什么", true);
        assertTrue(sent.isEmpty()); assertEquals(0, store.group("100", "300").callsToday());
        verifyNoInteractions(ai, groups, random);
    }
    @Test void oneAllowsEveryMessageWithoutRandomDraw() {
        store.saveGroup(policy(1, true));
        message("ping", true); message("我想吃什么", true);
        assertEquals(2, sent.size()); verifyNoInteractions(random);
        verify(groups).reply("100", "300", "200", "我想吃什么", false, false, false);
    }
    @Test void partialRateSamplesExactlyOncePerOrdinaryMessageAndUsesStrictBoundary() {
        store.saveGroup(policy(0.2, true));
        when(random.getAsDouble()).thenReturn(0.199999, 0.2, 0.7, 0.1);
        message("我想吃什么", true); // accepted
        message("我想吃什么", true); // rejected at threshold
        message("ping", true); // keyword also rejected
        message("ping", true); // keyword accepted
        assertEquals(2, sent.size()); verify(random, times(4)).getAsDouble();
        verify(groups, times(1)).reply(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
    }
    @Test void privateAndMentionedMessagesBypassZeroRateForKeywordsAndAi() {
        store.saveGroup(policy(0, true));
        message("[CQ:at,qq=100] 我想吃什么", true);
        message("[CQ:at,qq=100] ping", true);
        message("我想吃什么", false); message("ping", false);
        assertEquals(4, sent.size()); verifyNoInteractions(random);
        verify(groups).reply("100", "300", "200", "我想吃什么", true, false, false);
        verify(ai).chat("onebot:100:private:200", "我想吃什么");
    }
    @Test void mentionsStillRespectGroupConsent() {
        store.saveGroup(policy(1, false));
        message("[CQ:at,qq=100] 我想吃什么", true); message("[CQ:at,qq=100] ping", true);
        assertTrue(sent.isEmpty()); verifyNoInteractions(random, ai, groups);
    }
    @Test void validatesAndPersistsRateAndIncludesItInSqlRestore() throws Exception {
        for (double invalid : new double[]{-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(ResponseStatusException.class, () -> store.saveGroup(policy(invalid, true)));
        store.saveGroup(policy(0.23753, true));
        String backup = store.exportSql(); store.close();
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        assertEquals(0.23753, store.group("100", "300").autoReplyRate());
        Path restored = directory.resolve("restore"); Files.createDirectories(restored.resolve("data"));
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + restored.resolve("data/bot-config").toString().replace('\\', '/'), "sa", "");
             var sql = db.createStatement()) { sql.execute(backup); }
        var copy = new BotConfigurationService(restored.toString(), new OneBotProperties());
        try {
            assertEquals(0.23753, copy.group("100", "300").autoReplyRate());
        } finally { copy.close(); }
    }
    @Test void oldDatabaseGetsRateOneWithoutChangingEnabledFlag() throws Exception {
        store.saveGroup(policy(0.5, false)); store.close();
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + directory.resolve("data/bot-config").toString().replace('\\', '/'), "sa", "");
             var sql = db.createStatement()) { sql.execute("ALTER TABLE group_policy DROP COLUMN auto_reply_rate"); }
        store = new BotConfigurationService(directory.toString(), new OneBotProperties());
        assertEquals(1.0, store.group("100", "300").autoReplyRate());
        assertFalse(store.group("100", "300").enabled());
    }
}
