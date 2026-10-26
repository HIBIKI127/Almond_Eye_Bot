package org.almond_eye_bot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekBudgetTests {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"stop","length"})
    void batchLearningHasFixedBudgetNoSearchAndRequiresCompleteOutput(String finish) {
        var client = new DeepSeekClient();
        ReflectionTestUtils.setField(client,"apiKey","mock-key");
        ReflectionTestUtils.setField(client,"baseUrl","http://mock-ai");
        var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"restTemplate")).build();
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request -> {
            var body=JSON.parseObject(((MockClientHttpRequest)request).getBodyAsString());
            assertEquals(6144,body.getIntValue("max_tokens"));
            assertEquals("enabled",body.getJSONObject("thinking").getString("type"));
            assertEquals("json_object",body.getJSONObject("response_format").getString("type"));
            assertEquals(2,body.getJSONArray("messages").size());
            assertEquals("样本",body.getJSONArray("messages").getJSONObject(1).getString("content"));
        }).andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\""+finish+"\",\"message\":{\"content\":\"{}\"}}]}",MediaType.APPLICATION_JSON));
        var messages=List.of(JSONObject.of("role","user","content","样本"));
        if (finish.equals("stop")) assertEquals("{}",client.learnGroupStyle(messages));
        else assertThrows(DeepSeekClient.IncompleteSummaryException.class,()->client.learnGroupStyle(messages));
        server.verify();
    }
    @Test void thinkingCanBeDisabledExplicitlyAndNeverReturnsReasoningContent() {
        var client=clientWithoutSearch();
        ReflectionTestUtils.setField(client,"thinkingEnabled",false);
        ReflectionTestUtils.setField(client,"apiKey","mock-key");ReflectionTestUtils.setField(client,"baseUrl","http://mock-ai");
        var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"restTemplate")).build();
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request->{
            var body=JSON.parseObject(((MockClientHttpRequest)request).getBodyAsString());
            assertEquals("disabled",body.getJSONObject("thinking").getString("type"));
            assertEquals(64,body.getIntValue("max_tokens"));assertFalse(body.containsKey("reasoning_effort"));
        }).andRespond(withSuccess("{\"choices\":[{\"message\":{\"reasoning_content\":\"private reasoning\",\"content\":\"final answer\"}}]}",MediaType.APPLICATION_JSON));
        assertEquals("final answer",client.chat(List.of(JSONObject.of("role","user","content","问题")),"deepseek-chat",false,64));server.verify();
    }

    private DeepSeekClient clientWithoutSearch() {
        var client = new DeepSeekClient();
        ReflectionTestUtils.setField(client, "webSearchEnabled", false);
        return client;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "null", "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}", "{\"choices\":[{\"message\":{\"content\":[\"invalid\"]}}]}", "not-json"})
    void rejectsInvalidOutputInsteadOfReturningItAsChatText(String response) {
        var client = clientWithoutSearch();
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai");
        var rest = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://mock-ai/chat/completions")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(RuntimeException.class, () -> client.chat(List.of(JSONObject.of("role", "user", "content", "问题"))));
        server.verify();
    }

    @Test void transmitsOutputBudgetWithoutExternalNetwork() {
        var client = clientWithoutSearch();
        assertFalse(client.isConfigured());
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai");
        var rest = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request -> {
            var body = JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString());
            assertEquals(4160, body.getIntValue("max_tokens"));
            assertEquals("deepseek-flash", body.getString("model"));
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
            assertEquals("问题", body.getJSONArray("messages").getJSONObject(0).getString("content"));
        }).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"回复\"}}]}", MediaType.APPLICATION_JSON));
        assertEquals("回复", client.chat(List.of(JSONObject.of("role", "user", "content", "问题")), "deepseek-chat", false, 64));
        server.verify();
    }

    @Test void reasoningUsesConfiguredSupportedModelAndExplicitThinkingMode() {
        var client = clientWithoutSearch();
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai/");
        ReflectionTestUtils.setField(client, "reasoningModel", "deepseek-v4-pro");
        var rest = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request -> {
            var body = JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString());
            assertEquals("deepseek-v4-pro", body.getString("model"));
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
        }).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"回复\"}}]}", MediaType.APPLICATION_JSON));
        assertEquals("回复", client.chat(List.of(JSONObject.of("role", "user", "content", "问题")), "deepseek-reasoner", false));
        server.verify();
    }

    @Test void chatModelIsConfigurableAndInitialHistoryHasNoEmptyUserTurn() {
        var client = clientWithoutSearch();
        assertEquals(1, client.createMessageHistory("").size());
        assertEquals("system", client.createMessageHistory("").get(0).getString("role"));
        assertEquals(2, client.createMessageHistory("你好").size());
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai");
        ReflectionTestUtils.setField(client, "chatModel", "deepseek-v4-pro");
        var rest = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request -> {
            var body = JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString());
            assertEquals("deepseek-v4-pro", body.getString("model"));
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
            assertEquals("Bearer mock-key", request.getHeaders().getFirst("Authorization"));
        }).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"回复\"}}]}", MediaType.APPLICATION_JSON));
        assertEquals("回复", client.chat(client.createMessageHistory("你好")));
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"length", "content_filter", "tool_calls", "missing"})
    void rejectsIncompleteMemorySummariesEvenWhenSomeTextWasReturned(String finish) {
        var client = clientWithoutSearch();
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai");
        var rest = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        var server = MockRestServiceServer.bindTo(rest).build();
        var choice = JSONObject.of("message", JSONObject.of("content", "被截断的摘要"));
        if (!finish.equals("missing")) choice.put("finish_reason", finish);
        server.expect(requestTo("http://mock-ai/chat/completions"))
                .andRespond(withSuccess(JSONObject.of("choices", List.of(choice)).toJSONString(), MediaType.APPLICATION_JSON));
        assertThrows(RuntimeException.class, () -> client.summarize(List.of(JSONObject.of("role", "user", "content", "历史"))));
        server.verify();
    }

    @Test void summaryUsesBoundedThinkingRequestAndRequiresCompleteOutput() {
        var client = clientWithoutSearch();
        ReflectionTestUtils.setField(client, "apiKey", "mock-key");
        ReflectionTestUtils.setField(client, "baseUrl", "http://mock-ai");
        var rest = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://mock-ai/chat/completions")).andExpect(request -> {
            var body = JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString());
            assertEquals(6144, body.getIntValue("max_tokens"));
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
        }).andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"完整摘要\"}}]}", MediaType.APPLICATION_JSON));
        assertEquals("完整摘要", client.summarize(List.of(JSONObject.of("role", "user", "content", "历史"))));
        server.verify();
    }
}
