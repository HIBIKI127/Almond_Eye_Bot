package org.almond_eye_bot.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
public class DeepSeekClient {
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    @Value("${deepseek.base-url}")
    private String baseUrl;

    @Value("${deepseek.api-keys}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public String chat(List<JSONObject> messages) {
        return chat(messages, "deepseek-chat", false);
    }

    public String chat(List<JSONObject> messages, String model) {
        return chat(messages, model, false);
    }

    public String chat(List<JSONObject> messages, String model, boolean stream) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("stream", stream);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);

        logger.info("调用 DeepSeek API, model: {}", model);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/chat/completions",
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            JSONObject result = JSON.parseObject(response.getBody());
            JSONArray choices = result.getJSONArray("choices");

            if (choices != null && !choices.isEmpty()) {
                JSONObject firstChoice = choices.getJSONObject(0);
                JSONObject messageObj = firstChoice.getJSONObject("message");
                String content = messageObj.getString("content");
                logger.info("DeepSeek API 响应成功");
                return content;
            } else {
                logger.error("DeepSeek API 返回空响应：{}", response.getBody());
                throw new RuntimeException("AI 回复为空：" + response.getBody());
            }
        } catch (Exception e) {
            logger.error("调用 DeepSeek API 失败", e);
            throw new RuntimeException("调用 AI 服务失败：" + e.getMessage(), e);
        }
    }

    public JSONObject createMessage(String role, String content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    public List<JSONObject> createMessageHistory(String userMessage) {
        List<JSONObject> messages = new ArrayList<>();
        messages.add(createMessage("system", "你是一个 helpful assistant，请用中文和用户交流。"));
        messages.add(createMessage("user", userMessage));
        return messages;
    }
}
