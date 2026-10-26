package org.almond_eye_bot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bot.admin-token=test-admin", "onebot.enabled=false", "gocqhttp.enabled=false"})
class AdminApiTests {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpRequest.Builder api(String path) {
        return HttpRequest.newBuilder(uri("/admin/api" + path)).header("Authorization", "Bearer test-admin")
                .header("X-Requested-With", "AlmondAdmin");
    }
    private HttpResponse<String> send(HttpRequest request) throws Exception { return http.send(request, HttpResponse.BodyHandlers.ofString()); }

    @Test void exposesLocalShipStatusSwitchAndOfflinePreview() throws Exception {
        var state = JSON.parseObject(send(api("/state").GET().build()).body());
        assertTrue(state.containsKey("shipData"));
        boolean previous = state.getJSONObject("shipData").getBooleanValue("enabled");
        try {
            var saved = send(api("/ships/settings").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")).build());
            assertEquals(200, saved.statusCode(), saved.body()); assertFalse(JSON.parseObject(saved.body()).getBooleanValue("enabled"));
            var preview = send(api("/ships/preview").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"得梅因主炮\"}")).build());
            assertEquals(200, preview.statusCode(), preview.body()); assertFalse(JSON.parseObject(preview.body()).getBooleanValue("matched"));
            assertEquals(400, send(api("/ships/preview").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"\"}")).build()).statusCode());
        } finally { send(api("/ships/settings").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":" + previous + "}")).build()); }
    }

    @Test void managesAndPreviewsIndependentKnowledgeLibraries() throws Exception {
        var created = send(api("/knowledge").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"摄影手册\",\"keywords\":\"摄影,相机\",\"enabled\":true}")).build());
        assertEquals(201, created.statusCode(), created.body());
        long id = JSON.parseObject(created.body()).getLongValue("id");
        String boundary = "KnowledgeTestBoundary";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.txt\"\r\nContent-Type: text/plain\r\n\r\n摄影使用三脚架稳定相机。\r\n--" + boundary + "--\r\n";
        try {
            assertEquals(401, send(HttpRequest.newBuilder(uri("/admin/api/knowledge/" + id + "/files")).GET().build()).statusCode());
            var upload = send(api("/knowledge/" + id + "/files").header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(multipart)).build());
            assertEquals(201, upload.statusCode(), upload.body());
            long file = JSON.parseObject(upload.body()).getLongValue("id");
            assertEquals(1, JSON.parseArray(send(api("/knowledge/" + id + "/files").GET().build()).body()).size());
            var preview = send(api("/knowledge/preview").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"摄影怎么稳定相机\"}")).build());
            assertEquals(200, preview.statusCode(), preview.body());
            var result = JSON.parseObject(preview.body()); assertTrue(result.getBooleanValue("matched"));
            assertEquals(id, result.getJSONObject("retrieval").getLongValue("libraryId"));
            assertTrue(preview.body().contains("三脚架"));
            assertEquals(204, send(api("/knowledge/" + id + "/files/" + file).DELETE().build()).statusCode());
            assertEquals(200, send(api("/knowledge/" + id).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"摄影手册\",\"keywords\":\"摄影\",\"enabled\":false}")).build()).statusCode());
            assertFalse(JSON.parseObject(send(api("/knowledge/preview").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"摄影\"}")).build()).body()).getBooleanValue("matched"));
        } finally { assertEquals(204, send(api("/knowledge/" + id).DELETE().build()).statusCode()); }
    }

    @Test
    void servesIntegratedPageAndProtectsConfiguration() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(uri("/admin/index.html")).GET().build()).statusCode());
        assertEquals(200, send(HttpRequest.newBuilder(uri("/admin/admin.css")).GET().build()).statusCode());
        assertEquals(401, send(HttpRequest.newBuilder(uri("/admin/api/state")).GET().build()).statusCode());
        assertEquals(200, send(api("/state").GET().build()).statusCode());
        assertEquals(403, send(HttpRequest.newBuilder(uri("/admin/api/rules")).header("Authorization", "Bearer test-admin")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode());
    }

    @Test
    void savesAndPreviewsFunctionActionsWithoutCallingExternalServices() throws Exception {
        JSONObject rule = JSONObject.of("keyword", "咨询测试", "matchType", "CONTAINS", "replyText", "", "action", "AI",
                "enabled", true, "priority", 999);
        var created = send(api("/rules").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rule.toJSONString())).build());
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("AI", JSON.parseObject(created.body()).getString("action"));
        long id = JSON.parseObject(created.body()).getLongValue("id");
        var preview = send(api("/preview").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"请咨询测试 完整的问题\"}")).build());
        var matched = JSON.parseObject(preview.body()).getJSONObject("reply");
        assertEquals("AI", matched.getString("action")); assertEquals("完整的问题", matched.getString("argument"));
        rule.put("action", "UNKNOWN");
        assertEquals(400, send(api("/rules/" + id).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(rule.toJSONString())).build()).statusCode());
        assertEquals(204, send(api("/rules/" + id).DELETE().build()).statusCode());
    }

    @Test
    void managesPersonaThroughProtectedApiAndExposesSavedPrompt() throws Exception {
        String body = JSONObject.of("prompt", "你叫小杏，性格温柔，爱好游戏，偶尔说呀。").toJSONString();
        var saved = send(api("/persona").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
        assertEquals(200, saved.statusCode(), saved.body());
        var state = JSON.parseObject(send(api("/state").GET().build()).body());
        assertEquals("你叫小杏，性格温柔，爱好游戏，偶尔说呀。", state.getJSONObject("persona").getString("prompt"));
        assertEquals(400, send(api("/persona").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSONObject.of("prompt", "字".repeat(8001)).toJSONString())).build()).statusCode());
        assertEquals(400, send(api("/persona").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode());
        assertEquals(200, send(api("/persona").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"\"}")).build()).statusCode());
    }

    @Test
    void managesGroupPoliciesThroughProtectedApi() throws Exception {
        String body = JSON.toJSONString(GroupAutoReplyTests.policy("98765", "SMART", 50, 3));
        var saved = send(api("/groups").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(JSON.parseObject(saved.body()).getBooleanValue("enabled"));
        var updated = JSON.parseObject(body);
        updated.put("autoReplyRate", 0.15);
        var rateSaved = send(api("/groups").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(updated.toJSONString())).build());
        assertEquals(200, rateSaved.statusCode(), rateSaved.body());
        assertEquals(0.15, JSON.parseObject(rateSaved.body()).getDoubleValue("autoReplyRate"));
        updated.put("autoReplyRate", 1.01);
        assertEquals(400, send(api("/groups").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(updated.toJSONString())).build()).statusCode());
        var state = JSON.parseObject(send(api("/state").GET().build()).body());
        assertTrue(state.getJSONArray("groups").toJSONString().contains("98765"));
        assertTrue(state.containsKey("aiConfigured"));
        assertEquals(400, send(api("/groups").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.replace("\"cooldownSeconds\":30", "\"cooldownSeconds\":0"))).build()).statusCode());
        assertEquals(204, send(api("/groups/100/98765").DELETE().build()).statusCode());
    }

    @Test void groupCanCarryUpToFiftyRecentMemoryTurns() throws Exception {
        String body = JSON.toJSONString(GroupAutoReplyTests.policy("98766", "ALL", 50, 50));
        var saved = send(api("/groups").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(50, JSON.parseObject(saved.body()).getIntValue("contextTurns"));
        assertEquals(400, send(api("/groups").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.replace("\"contextTurns\":50", "\"contextTurns\":51"))).build()).statusCode());
        assertEquals(204, send(api("/groups/100/98766").DELETE().build()).statusCode());
    }

    @Test
    void createsUploadsMatchesUpdatesAndDeletesThroughHttp() throws Exception {
        byte[] png = BotConfigurationServiceTests.png();
        var multipart = new ByteArrayOutputStream();
        multipart.write(("--almond-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(png);
        multipart.write("\r\n--almond-test--\r\n".getBytes(StandardCharsets.UTF_8));
        var upload = send(api("/images").header("Content-Type", "multipart/form-data; boundary=almond-test")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build());
        assertEquals(201, upload.statusCode(), upload.body());
        long imageId = JSON.parseObject(upload.body()).getLongValue("id");
        var content = http.send(api("/images/" + imageId + "/content").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, content.statusCode());
        assertArrayEquals(png, content.body());
        JSONObject rule = JSONObject.of("keyword", "测试规则", "matchType", "EXACT", "replyText", "你好", "imageId", imageId,
                "enabled", true, "priority", 100);
        var created = send(api("/rules").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rule.toJSONString())).build());
        assertEquals(201, created.statusCode(), created.body());
        long id = JSON.parseObject(created.body()).getLongValue("id");
        assertEquals(409, send(api("/images/" + imageId).DELETE().build()).statusCode());
        var preview = send(api("/preview").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"测试规则\"}")).build());
        assertTrue(JSON.parseObject(preview.body()).getBooleanValue("matched"));
        assertEquals("你好", JSON.parseObject(preview.body()).getJSONObject("reply").getString("text"));
        rule.put("replyText", "更新后的回复");
        rule.put("imageId", null);
        assertEquals(200, send(api("/rules/" + id).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(rule.toJSONString())).build()).statusCode());
        var invalid = send(api("/rules").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        assertEquals(400, invalid.statusCode());
        var exported = send(api("/export.sql").GET().build());
        assertEquals(200, exported.statusCode());
        assertTrue(exported.body().contains("INSERT INTO reply_rule"));
        assertEquals(204, send(api("/rules/" + id).DELETE().build()).statusCode());
        assertEquals(204, send(api("/images/" + imageId).DELETE().build()).statusCode());
    }
}
