package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiChatService {
    private static final Logger logger = LoggerFactory.getLogger(AiChatService.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    private final ConcurrentHashMap<String, List<JSONObject>> conversationHistories = new ConcurrentHashMap<>();

    public String chat(String userId, String userMessage) {
        try {
            List<JSONObject> messages = getOrCreateConversation(userId);

            JSONObject userMsg = deepSeekClient.createMessage("user", userMessage);
            messages.add(userMsg);

            String aiResponse = deepSeekClient.chat(messages, "deepseek-chat", false);

            if (aiResponse != null && !aiResponse.trim().isEmpty()) {
                JSONObject aiMsg = deepSeekClient.createMessage("assistant", aiResponse);
                messages.add(aiMsg);

                updateConversation(userId, messages);

                return aiResponse;
            } else {
                logger.warn("AI 返回空响应");
                return "抱歉，我暂时无法回答您的问题。";
            }
        } catch (Exception e) {
            logger.error("AI 聊天失败", e);
            return "AI 服务暂时不可用，请稍后再试。（错误：" + e.getMessage() + "）";
        }
    }

    public String chatWithReasoning(String userId, String userMessage) {
        try {
            List<JSONObject> messages = getOrCreateConversation(userId);

            JSONObject userMsg = deepSeekClient.createMessage("user", userMessage);
            messages.add(userMsg);

            String aiResponse = deepSeekClient.chat(messages, "deepseek-reasoner", false);

            if (aiResponse != null && !aiResponse.trim().isEmpty()) {
                JSONObject aiMsg = deepSeekClient.createMessage("assistant", aiResponse);
                messages.add(aiMsg);

                updateConversation(userId, messages);

                return aiResponse;
            } else {
                logger.warn("AI 返回空响应");
                return "抱歉，我暂时无法回答您的问题。";
            }
        } catch (Exception e) {
            logger.error("AI 深度思考聊天失败", e);
            return "AI 深度思考服务暂时不可用，请稍后再试。（错误：" + e.getMessage() + "）";
        }
    }

    private List<JSONObject> getOrCreateConversation(String userId) {
        return conversationHistories.computeIfAbsent(userId, k ->
                deepSeekClient.createMessageHistory("")
        );
    }

    private void updateConversation(String userId, List<JSONObject> messages) {
        conversationHistories.put(userId, messages);
    }

    public void clearHistory(String userId) {
        conversationHistories.remove(userId);
        logger.info("已清除用户 {} 的聊天历史", userId);
    }

    public int getHistorySize(String userId) {
        List<JSONObject> history = conversationHistories.get(userId);
        return history != null ? history.size() : 0;
    }
}
