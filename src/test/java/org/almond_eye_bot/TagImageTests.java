package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TagImageTests {
    static class Store extends BotConfigurationService implements AutoCloseable {
        Store(Path path) throws Exception { super(path.toString(), new OneBotProperties()); }
    }
    TagImageService images;
    MockRestServiceServer server;
    Store history;
    @TempDir Path directory;
    @BeforeEach void setup() throws Exception {
        history = new Store(directory.resolve("history"));
        images = new TagImageService();
        ReflectionTestUtils.setField(images, "configuration", history);
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(images, "restTemplate")).build();
    }
    @AfterEach void closeHistory() throws Exception { history.close(); }
    JSONObject post(int id, String rating, String url) {
        return JSONObject.of("id", id, "rating", rating, "file_url", url, "file_ext", "png", "file_size", 80);
    }
    void posts(List<JSONObject> rows) {
        server.expect(request -> {
            assertEquals("/posts.json", request.getURI().getPath());
            String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("rating:g order:random"));
            assertTrue(query.contains("limit=20"));
        }).andRespond(withSuccess(com.alibaba.fastjson2.JSON.toJSONString(rows), MediaType.APPLICATION_JSON));
    }
    byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    @Test void encodesTagsDownloadsImageAndNeverLeaksApiCredentialsToCdn() throws Exception {
        ReflectionTestUtils.setField(images, "login", "test-user");
        ReflectionTestUtils.setField(images, "apiKey", "test-key");
        server.expect(request -> {
            String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("tags=hatsune_miku blue_hair rating:g order:random"));
            assertNotNull(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        }).andRespond(withSuccess("[" + post(123, "g", "https://cdn.donmai.us/a.png") + "]", MediaType.APPLICATION_JSON));
        byte[] bytes = png();
        server.expect(requestTo("https://cdn.donmai.us/a.png")).andExpect(request ->
                assertNull(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)))
                .andRespond(withSuccess(bytes, MediaType.IMAGE_PNG));
        var result = images.search("HATSUNE_MIKU   blue_hair");
        assertArrayEquals(bytes, Base64.getDecoder().decode(result.imageBase64()));
        assertEquals("图片来源：https://danbooru.donmai.us/posts/123", result.text());
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"rating:e", "cat | rating:e", "cat dog bird", "-cat", "cat&limit=100", "cat ~dog", ""})
    void rejectsUnsupportedQueriesBeforeNetworking(String query) {
        assertNull(images.search(query).imageBase64()); server.verify();
    }

    @Test void rejectsOtherRatingsDeletedAndUntrustedUrlsBeforeDownload() {
        var deleted = post(5, "g", "https://cdn.donmai.us/deleted.png"); deleted.put("is_deleted", true);
        posts(List.of(post(1, "e", "https://cdn.donmai.us/a.png"), post(2, "s", "https://cdn.donmai.us/b.png"),
                post(3, "g", "https://127.0.0.1/a.png"), post(4, "g", "https://cdn.donmai.us.evil.example/a.png"), deleted,
                post(6, "g", "http://cdn.donmai.us/a.png")));
        var result = images.search("cat");
        assertNull(result.imageBase64()); assertTrue(result.text().contains("未找到")); server.verify();
    }

    @Test void malformedImageAndRedirectAreNotForwardedToChat() {
        posts(List.of(post(1, "g", "https://cdn.donmai.us/a.png"), post(2, "g", "https://cdn.donmai.us/b.png")));
        server.expect(requestTo("https://cdn.donmai.us/a.png")).andRespond(withSuccess("<html>error</html>", MediaType.TEXT_HTML));
        server.expect(requestTo("https://cdn.donmai.us/b.png")).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "http://127.0.0.1/private"));
        assertNull(images.search("cat").imageBase64()); server.verify();
    }

    @Test void oversizedResponseIsRejectedAndThrottleStopsNextCall() {
        posts(List.of(post(1, "g", "https://cdn.donmai.us/a.png")));
        server.expect(requestTo("https://cdn.donmai.us/a.png"))
                .andRespond(withSuccess(new byte[8 * 1024 * 1024 + 1], MediaType.IMAGE_PNG));
        assertNull(images.search("cat").imageBase64());
        assertTrue(images.search("cat").text().contains("太频繁")); server.verify();
    }

    @Test void disabledAndUpstreamFailureReturnUsefulMessages() {
        ReflectionTestUtils.setField(images, "enabled", false);
        assertTrue(images.search("cat").text().contains("关闭"));
        ReflectionTestUtils.setField(images, "enabled", true);
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertTrue(images.search("cat").text().contains("失败")); server.verify();
    }

    @Test void proxyConfigurationAcceptsExplicitHttpProxyAndRejectsInvalidAddresses() {
        assertDoesNotThrow(() -> images.setProxyUrl(""));
        assertDoesNotThrow(() -> images.setProxyUrl("http://127.0.0.1:7897"));
        assertThrows(IllegalArgumentException.class, () -> images.setProxyUrl("socks5://127.0.0.1:7897"));
        assertThrows(IllegalArgumentException.class, () -> images.setProxyUrl("http://user:secret@127.0.0.1:7897"));
    }

    @Test void newRuleSupportsArgumentsRenameRemovalAndRestartWithoutResurrection() throws Exception {
        long id;
        try (var store = new Store(directory)) {
            var found = store.match("搜图 hatsune_miku blue_hair").orElseThrow();
            assertEquals("TAG_IMAGE", found.action()); assertEquals("hatsune_miku blue_hair", found.argument());
            assertTrue(store.match("搜图").orElseThrow().argument().isEmpty());
            assertTrue(store.match("搜图片").isEmpty());
            id = store.rules().stream().filter(rule -> rule.action().equals("TAG_IMAGE")).findFirst().orElseThrow().id();
            store.saveRule(id, new RuleInput("找图", "EXACT", "用法：{keyword} tag", null, true, 20, "TAG_IMAGE"));
            assertTrue(store.match("搜图 cat").isEmpty());
            assertEquals("cat", store.match("找图 cat").orElseThrow().argument());
            store.deleteRule(id);
        }
        try (var store = new Store(directory)) {
            assertTrue(store.rules().stream().noneMatch(rule -> rule.action().equals("TAG_IMAGE")));
        }
    }

    @Test void routesThroughOnebotWithoutAiAndRespectsGroupGateAndRate() throws Exception {
        try (var store = new Store(directory)) {
            var ai = mock(AiChatService.class); var groups = mock(GroupAutoReplyService.class);
            var tag = mock(TagImageService.class);
            when(tag.isEnabled()).thenReturn(true);
            when(tag.search("cat", "group:100:300")).thenReturn(new TagImageService.Result("来源", "aW1hZ2U="));
            var replies = new BotReplyService(ai, mock(LocalImageService.class), mock(WikiService.class), store, groups);
            ReflectionTestUtils.setField(replies, "tagImages", tag);
            store.saveSettings(new Settings(false, false, false, false, false));
            var onebot = new OneBotMessageService(store, replies, groups);
            List<Map<String, Object>> sent = new ArrayList<>();
            var event = JSONObject.of("self_id", 100, "user_id", 200, "group_id", 300, "message_type", "group", "message", "搜图 cat");
            onebot.handleEvent(event, (action, params) -> sent.add(params));
            verifyNoInteractions(tag); assertTrue(sent.isEmpty());
            var p = GroupAutoReplyTests.policy("300", "ALL", 10, 1);
            store.saveGroup(new GroupPolicy(p.selfId(), p.groupId(), p.groupName(), true, p.mode(), p.keywords(),
                    p.cooldownSeconds(), p.dailyLimit(), p.maxInputChars(), p.maxOutputTokens(), p.contextTurns(), p.followUpSeconds(), 0, 0.0));
            onebot.handleEvent(event, (action, params) -> sent.add(params));
            verifyNoInteractions(tag);
            store.saveGroup(p);
            onebot.handleEvent(event, (action, params) -> sent.add(params));
            assertEquals(2, sent.size()); assertTrue(sent.get(1).toString().contains("base64://aW1hZ2U="));
            event.put("user_id", 201);
            onebot.handleEvent(event, (action, params) -> sent.add(params));
            verify(tag, times(2)).search("cat", "group:100:300"); verifyNoInteractions(ai, groups);
            assertEquals(0, store.group("100", "300").callsToday());
        }
    }

    @Test void skipsRecentImagesAcrossTagOrderAndDeduplicatesMd5AcrossPostIds() throws Exception {
        var first = post(1, "g", "https://cdn.donmai.us/first.png");
        first.put("md5", "a".repeat(32));
        var duplicate = post(99, "g", "https://cdn.donmai.us/duplicate.png");
        duplicate.put("md5", "a".repeat(32));
        var fresh = post(2, "g", "https://cdn.donmai.us/second.png");
        fresh.put("md5", "b".repeat(32));
        posts(List.of(first));
        server.expect(requestTo("https://cdn.donmai.us/first.png")).andRespond(withSuccess(png(), MediaType.IMAGE_PNG));
        posts(List.of(duplicate, fresh));
        server.expect(requestTo("https://cdn.donmai.us/second.png")).andRespond(withSuccess(png(), MediaType.IMAGE_PNG));
        assertTrue(images.search("cat blue_hair", "group:100:300").text().endsWith("/1"));
        ReflectionTestUtils.setField(images, "nextRequest", 0L);
        assertTrue(images.search("blue_hair cat", "group:100:300").text().endsWith("/2"));
        server.verify();
    }

    @Test void newRandomBatchesFindUnseenImagesButNeverSilentlyRepeatWhenExhausted() throws Exception {
        var first = post(1, "g", "https://cdn.donmai.us/a.png");
        var second = post(2, "g", "https://cdn.donmai.us/b.png");
        posts(List.of(first));
        server.expect(requestTo("https://cdn.donmai.us/a.png")).andRespond(withSuccess(png(), MediaType.IMAGE_PNG));
        posts(List.of(first)); posts(List.of(first, second));
        server.expect(requestTo("https://cdn.donmai.us/b.png")).andRespond(withSuccess(png(), MediaType.IMAGE_PNG));
        posts(List.of(first, second)); posts(List.of(first, second)); posts(List.of(first, second));
        assertNotNull(images.search("cat", "user").imageBase64());
        ReflectionTestUtils.setField(images, "nextRequest", 0L);
        assertTrue(images.search("cat", "user").text().endsWith("/2"));
        ReflectionTestUtils.setField(images, "nextRequest", 0L);
        var exhausted = images.search("cat", "user");
        assertNull(exhausted.imageBase64()); assertTrue(exhausted.text().contains("未重复"));
        server.verify();
    }

    @Test void targetsAreIndependentAndFailedDownloadsDoNotEnterHistory() throws Exception {
        var first = post(1, "g", "https://cdn.donmai.us/a.png");
        posts(List.of(first));
        server.expect(requestTo("https://cdn.donmai.us/a.png")).andRespond(withSuccess("bad", MediaType.TEXT_PLAIN));
        posts(List.of(first));
        server.expect(requestTo("https://cdn.donmai.us/a.png")).andRespond(withSuccess(png(), MediaType.IMAGE_PNG));
        posts(List.of(first));
        server.expect(requestTo("https://cdn.donmai.us/a.png")).andRespond(withSuccess(png(), MediaType.IMAGE_PNG));
        assertNull(images.search("cat", "group:100:300").imageBase64());
        ReflectionTestUtils.setField(images, "nextRequest", 0L);
        assertNotNull(images.search("cat", "group:100:300").imageBase64());
        ReflectionTestUtils.setField(images, "nextRequest", 0L);
        assertNotNull(images.search("cat", "group:100:301").imageBase64());
        server.verify();
    }

    @Test void historySurvivesRestartExpiresAndIsBoundedAndNotExported() throws Exception {
        String target = "a".repeat(64);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 205; i++) history.rememberTagImage(target, String.format("%064d", i), now + i);
        var recent = history.recentTagImages(target, now);
        assertEquals(200, recent.size());
        assertFalse(recent.contains(String.format("%064d", 0)));
        assertTrue(history.recentTagImages(target, now + 8 * 24 * 60 * 60 * 1000L).isEmpty());
        String sql = history.exportSql();
        assertTrue(sql.contains("DELETE FROM tag_image_history"));
        assertFalse(sql.contains("INSERT INTO tag_image_history"));
        history.close();
        history = new Store(directory.resolve("history"));
        assertEquals(recent, history.recentTagImages(target, now));
    }
}
