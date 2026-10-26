package org.almond_eye_bot;

import org.almond_eye_bot.client.GoCQHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GoCQHttpClientTests {
    @Test
    void actionEndpointUsesBareParamsAndBearerToken() {
        GoCQHttpClient client = new GoCQHttpClient();
        ReflectionTestUtils.setField(client, "apiUrl", "http://localhost:5700/");
        ReflectionTestUtils.setField(client, "accessToken", "test-token");
        RestTemplate rest = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("http://localhost:5700/send_group_msg"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().json("{\"group_id\":300,\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"pong\"}}]}"))
                .andRespond(withSuccess("{\"status\":\"ok\",\"retcode\":0,\"data\":{\"message_id\":1}}", MediaType.APPLICATION_JSON));
        var result = client.callApi("send_group_msg", Map.of("group_id", 300L, "message",
                List.of(Map.of("type", "text", "data", Map.of("text", "pong")))));
        assertNotNull(result);
        assertEquals(0, result.getIntValue("retcode"));
        server.verify();
    }
}
