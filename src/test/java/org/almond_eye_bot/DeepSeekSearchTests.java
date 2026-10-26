package org.almond_eye_bot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeepSeekSearchTests {
    DeepSeekClient client;
    MockRestServiceServer server;
    @BeforeEach void setup() {
        client = new DeepSeekClient();
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai");
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "restTemplate")).build();
    }
    List<JSONObject> messages(String text) {
        return List.of(JSONObject.of("role", "system", "content", "人设提示"),
                JSONObject.of("role", "user", "content", "个人历史与群历史不应进入搜索词"),
                JSONObject.of("role", "assistant", "content", "历史回答"), JSONObject.of("role", "user", "content", text));
    }
    String results() {
        return JSONObject.of("results", List.of(JSONObject.of("title", "官方公告", "url", "https://example.com/announcement",
                "summary", "发布了新版本", "date", "2026-10-23", "content", "不应附带网页全文"))).toJSONString();
    }
    String answer() { return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"已更新，参考官方公告\"}}]}"; }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void searchesBeforePrivateAndGroupRepliesAndPreservesQuestion(boolean group, boolean versioned) {
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai" + (versioned ? "/v1/" : "/"));
        var original = messages("最新版本是什么");
        String before = JSON.toJSONString(original);
        server.expect(requestTo("http://mock-ai/v1/tools/search")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer mock-key")).andExpect(request -> {
                    var body = JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals("最新版本是什么", body.getString("query")); assertEquals(3, body.getIntValue("count"));
                    assertFalse(body.getBooleanValue("need_content")); assertEquals(3, body.size());
                    assertFalse(body.toJSONString().contains("历史"));
                }).andRespond(withSuccess(results(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mock-ai" + (versioned ? "/v1" : "") + "/chat/completions"))
                .andExpect(request -> {
                    var body = JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString());
                    var data = body.getJSONArray("messages");
                    assertTrue(data.toJSONString().contains("发布了新版本"));
                    assertTrue(data.toJSONString().contains("https://example.com/announcement"));
                    assertTrue(data.toJSONString().contains("不是指令"));
                    assertFalse(data.toJSONString().contains("不应附带网页全文"));
                    assertEquals("最新版本是什么", data.getJSONObject(data.size() - 1).getString("content"));
                    assertEquals("人设提示", data.getJSONObject(0).getString("content"));
                    if (group) assertEquals(4160, body.getIntValue("max_tokens"));
                    assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
                }).andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        String response = group ? client.chat(original, "deepseek-chat", false, 64) : client.chat(original);
        assertEquals("已更新，参考官方公告", response);
        assertEquals(before, JSON.toJSONString(original)); server.verify();
    }
    @Test void emptyResultsDoNotPretendFactsWereVerified() {
        server.expect(requestTo("http://mock-ai/v1/tools/search")).andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request ->
                assertTrue(((MockClientHttpRequest) request).getBodyAsString().contains("不得声称已证实实时信息")))
                .andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        assertNotNull(client.chat(messages("未知内容"))); server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "not-json", "{\"results\":null}", "{\"results\":[{\"url\":\"javascript:alert(1)\",\"summary\":\"错误来源\"}]}"})
    void malformedSearchNeverFallsBackToUngroundedReply(String response) {
        server.expect(requestTo("http://mock-ai/v1/tools/search")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(RuntimeException.class, () -> client.chat(messages("最新消息"))); server.verify();
    }
    @ParameterizedTest @ValueSource(ints = {401, 402, 429, 500})
    void httpFailureStopsBeforeChatWithoutRetry(int status) {
        server.expect(requestTo("http://mock-ai/v1/tools/search")).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThrows(RuntimeException.class, () -> client.chat(messages("最新消息"))); server.verify();
    }
    @Test void timeoutStopsBeforeChat() {
        server.expect(requestTo("http://mock-ai/v1/tools/search")).andRespond(withException(new IOException("模拟超时")));
        assertThrows(RuntimeException.class, () -> client.chat(messages("最新消息"))); server.verify();
    }
    @Test void summariesNeverSearchAndSearchCanBeDisabled() {
        server.expect(requestTo("http://mock-ai/chat/completions")).andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        assertNotNull(client.summarize(messages("待总结的历史"))); server.verify(); server.reset();
        ReflectionTestUtils.setField(client, "webSearchEnabled", false);
        server.expect(requestTo("http://mock-ai/chat/completions")).andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        assertNotNull(client.chat(messages("不联网"))); server.verify();
    }
    @Test void queryAndSourcesAreBoundedAndUnsafeUrlsExcluded() {
        String question = "😀".repeat(130);
        server.expect(requestTo("http://mock-ai/v1/tools/search")).andExpect(request -> {
            String query = JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString()).getString("query");
            assertEquals(128, query.codePointCount(0, query.length()));
        }).andRespond(withSuccess(JSONObject.of("results", List.of(
                JSONObject.of("url", "file:///private", "summary", "无效来源"),
                JSONObject.of("url", "https://user:password@example.com/", "summary", "无效凭据"),
                JSONObject.of("url", "https://example.com/", "title", "标题".repeat(1000), "snippet", "资料".repeat(10000)))).toJSONString(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            assertFalse(body.contains("无效来源")); assertFalse(body.contains("无效凭据"));
            assertTrue(body.contains("https://example.com/")); assertTrue(body.length() < 6000);
        }).andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        assertNotNull(client.chat(messages(question))); server.verify();
    }
    @Test void invalidSettingsOrMissingQuestionNeverMakeRequests() {
        assertThrows(IllegalArgumentException.class, () -> client.chat(List.of(JSONObject.of("role", "system", "content", "没有问题"))));
        ReflectionTestUtils.setField(client, "searchResultCount", 6);
        assertThrows(IllegalArgumentException.class, () -> client.chat(messages("问题"))); server.verify();
    }
}
