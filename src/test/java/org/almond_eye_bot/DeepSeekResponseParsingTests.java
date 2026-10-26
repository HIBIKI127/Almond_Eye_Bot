package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekResponseParsingTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    DeepSeekClient client(boolean search) {
        var client = new DeepSeekClient();
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai");
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "webSearchEnabled", search);
        return client;
    }
    MockRestServiceServer server(DeepSeekClient client) {
        return MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "restTemplate")).build();
    }
    List<JSONObject> question() { return List.of(JSONObject.of("role", "user", "content", "最近有什么新消息")); }
    String response(String text) {
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", text)))));
    }
    String text(Random random) {
        var text = new StringBuilder("中");
        for (int j = 0; j < 10; j++) text.append("文".repeat(20 + random.nextInt(900))).append('\n');
        return text.toString();
    }

    @Test void parsesChangingLengthSearchSummariesWithoutUtf16BufferReuseFailure() {
        var client = client(true);
        var server = server(client);
        var random = new Random(3989);
        // The same mixed-size, escaped-string pattern reproduces the 2.0.61
        // readString arraycopy failure. Parse every source before truncating it.
        for (int trial = 0; trial < 8; trial++) {
            var sources = new ArrayList<Map<String, String>>();
            for (int i = 0; i < 5; i++) sources.add(Map.of("title", "中文标题", "url", "https://example.com/" + i,
                    "summary", text(random), "content", "不参与检索上下文的全文"));
            server.expect(requestTo("http://mock-ai/v1/tools/search"))
                    .andRespond(withSuccess(JSON.writeValueAsString(Map.of("results", sources)), MediaType.APPLICATION_JSON));
            String expectedSummary = sources.get(0).get("summary").substring(0, 1200);
            server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request -> {
                // Avoid using the faulty parser in the assertion itself.
                var sent = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString()).get("messages");
                String data = sent.get(sent.size() - 2).get("content").asString();
                var payload = JSON.readTree(data.substring(data.indexOf('\n') + 1));
                assertEquals(3, payload.get("results").size());
                assertEquals(expectedSummary, payload.get("results").get(0).get("summary").asString());
                assertFalse(data.contains("不参与检索上下文的全文"));
            }).andRespond(withSuccess(response("已检索最新资料"), MediaType.APPLICATION_JSON));
        }
        for (int trial = 0; trial < 8; trial++) assertEquals("已检索最新资料", client.chat(question()));
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints = {32, 560, 720, 8192, 40000})
    void chatAndSummaryPreserveLongUnicodeEscapedContent(int size) {
        var client = client(false);
        var server = server(client);
        String content = "中文😀\n\t\"引号\"\\路径\r\n" + "长".repeat(size) + "\n结尾";
        server.expect(requestTo("http://mock-ai/chat/completions")).andRespond(withSuccess(response("短\n回复"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mock-ai/chat/completions")).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mock-ai/chat/completions")).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
        assertEquals("短\n回复", client.chat(question()));
        assertEquals(content, client.chat(question()));
        assertEquals(content, client.summarize(question()));
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "\"text\"", "{\"results\":[]} {}", "{\"results\":{}}", "{\"results\":[{\"summary\":\"未结束"})
    void malformedSearchJsonStillFailsWithoutSendingAChatRequest(String body) {
        var client = client(true);
        var server = server(client);
        server.expect(requestTo("http://mock-ai/v1/tools/search")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(RuntimeException.class, () -> client.chat(question()));
        server.verify();
    }

    @Test void sharedResponseParserHandlesConcurrentLongEscapedReplies() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) {
                int number = i;
                jobs.add(pool.submit(() -> {
                    var client = client(true);
                    var server = server(client);
                    String content = text(new Random(number)) + "\"😀\\";
                    String results = JSON.writeValueAsString(Map.of("results", List.of(Map.of(
                            "title", "新闻", "url", "https://example.com/", "summary", content))));
                    server.expect(requestTo("http://mock-ai/v1/tools/search")).andRespond(withSuccess(results, MediaType.APPLICATION_JSON));
                    server.expect(requestTo("http://mock-ai/chat/completions")).andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));
                    assertEquals(content, client.chat(question())); server.verify();
                }));
            }
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
}
