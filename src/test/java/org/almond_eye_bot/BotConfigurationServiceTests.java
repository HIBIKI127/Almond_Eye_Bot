package org.almond_eye_bot;

import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.BotConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class BotConfigurationServiceTests {
    @TempDir Path directory;

    @org.junit.jupiter.api.BeforeEach void isolateCustomRuleFixtures() throws Exception {
        var store = open();
        try { for (var rule : store.rules()) store.deleteRule(rule.id()); }
        finally { store.close(); }
    }

    private BotConfigurationService open() throws Exception {
        return new BotConfigurationService(directory.toString(), new OneBotProperties());
    }

    static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    @Test
    void persistsSettingsAndRulesAcrossRestartAndUsesPriority() throws Exception {
        var store = open();
        try {
            store.saveRule(null, new RuleInput("你好", "CONTAINS", "低优先级", null, true, 0));
            var high = store.saveRule(null, new RuleInput("你好", "EXACT", "高优先级", null, true, 10));
            assertEquals("高优先级", store.match("你好").orElseThrow().text());
            assertEquals("低优先级", store.match("你好呀").orElseThrow().text());
            store.saveRule(high.id(), new RuleInput("你好", "EXACT", "已停用", null, false, 10));
            assertEquals("低优先级", store.match("你好").orElseThrow().text());
            store.saveSettings(new Settings(false, true, false, false, false));
            assertTrue(store.match("你好").isEmpty());
        } finally { store.close(); }
        store = open();
        try {
            assertEquals(2, store.rules().size());
            assertFalse(store.settings().aiEnabled());
            assertFalse(store.settings().groupRequireAt());
            assertFalse(store.settings().keywordRepliesEnabled());
        } finally { store.close(); }
    }

    @Test
    void uploadIsStoredInRootAndReferencedImagesCannotBeDeleted() throws Exception {
        var store = open();
        try {
            byte[] bytes = png();
            var image = store.upload(new MockMultipartFile("file", "../../test.png", "text/html", bytes));
            assertEquals("test.png", image.originalName());
            assertEquals("image/png", image.contentType());
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("uploads/images").resolve(image.storedName())));
            var rule = store.saveRule(null, new RuleInput("表情", "EXACT", "给你", image.id(), true, 0));
            assertEquals(Base64.getEncoder().encodeToString(bytes), store.match("表情").orElseThrow().imageBase64());
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> store.deleteImage(image.id())).getStatusCode().value());
            store.deleteRule(rule.id());
            store.deleteImage(image.id());
            assertFalse(Files.exists(directory.resolve("uploads/images").resolve(image.storedName())));
            assertTrue(store.images().isEmpty());
        } finally { store.close(); }
    }

    @Test
    void validatesRulesAndRejectsDisguisedUploads() throws Exception {
        var store = open();
        try {
            assertThrows(ResponseStatusException.class, () -> store.saveRule(null, new RuleInput(" ", "EXACT", "hi", null, true, 0)));
            assertThrows(ResponseStatusException.class, () -> store.saveRule(null, new RuleInput("hi", "REGEX", "hi", null, true, 0)));
            assertThrows(ResponseStatusException.class, () -> store.saveRule(null, new RuleInput("hi", "EXACT", "", null, true, 0)));
            assertThrows(ResponseStatusException.class, () -> store.saveRule(null, new RuleInput("hi", "EXACT", "hi", null, true, -1)));
            assertThrows(ResponseStatusException.class, () -> store.saveRule(null, new RuleInput("hi", "EXACT", "hi", 99L, true, 0)));
            assertThrows(ResponseStatusException.class, () -> store.saveSettings(new Settings(null, true, true, true, true)));
            assertThrows(ResponseStatusException.class, () -> store.upload(new MockMultipartFile("file", "fake.png", "image/png", "<script>alert(1)</script>".getBytes())));
            assertTrue(store.images().isEmpty());
        } finally { store.close(); }
    }

    @Test
    void sqlExportRoundTripsQuotesAndRestoresIdentityCounters() throws Exception {
        String sql;
        var store = open();
        try {
            var image = store.upload(new MockMultipartFile("file", "a'b.png", "image/png", png()));
            store.saveRule(null, new RuleInput("quote';DROP TABLE reply_rule;--", "EXACT", "hello 'world'\n第二行", image.id(), true, 30));
            sql = store.exportSql();
        } finally { store.close(); }
        Path restored = directory.resolve("restored");
        Files.createDirectories(restored.resolve("data"));
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + restored.resolve("data/bot-config").toString().replace('\\', '/'), "sa", "");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
        var copy = new BotConfigurationService(restored.toString(), new OneBotProperties());
        try {
            assertEquals("quote';DROP TABLE reply_rule;--", copy.rules().get(0).keyword());
            assertEquals("hello 'world'\n第二行", copy.rules().get(0).replyText());
            assertEquals("a'b.png", copy.images().get(0).originalName());
            assertTrue(copy.saveRule(null, new RuleInput("new", "EXACT", "new", null, true, 0)).id() > 1);
        } finally { copy.close(); }
    }
}
