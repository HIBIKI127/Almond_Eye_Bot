package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.almond_eye_bot.client.DeepSeekClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;

@Slf4j
@Service
public class AiChatService {
    @Autowired private DeepSeekClient deepSeekClient;
    @Autowired private BotConfigurationService configuration;
    @Autowired private ConversationMemoryService memory;

    public String chat(String userId, String userMessage) { return reply(userId, userMessage, "deepseek-chat"); }
    public String chatWithReasoning(String userId, String userMessage) { return reply(userId, userMessage, "deepseek-reasoner"); }

    private String reply(String conversation, String text, String model) {
        if (text == null || text.isBlank() || !deepSeekClient.isConfigured()) return null;
        String owner = ConversationMemoryService.owner(conversation);
        String[] scope = conversation != null && conversation.matches("onebot:[1-9][0-9]*:group:[1-9][0-9]*:[1-9][0-9]*") ? conversation.split(":") : null;
        synchronized (memory.lock(owner)) {
            try {
                var messages = new ArrayList<JSONObject>();
                var boundary = configuration.contentBoundary();
                var assigned = scope == null ? null : configuration.groupPersona(scope[1],scope[3]);
                String persona = assigned == null ? configuration.systemPrompt(false) : configuration.systemPrompt(assigned);
                messages.add(JSONObject.of("role", "system", "content", persona));
                messages.addAll(PersonaContext.history(memory.context(owner, 50, 4000, 4000)));
                String input = limit(text);
                new KnowledgeBaseService(configuration).augment(messages, input);
                messages.add(JSONObject.of("role", "user", "content", input));
                String response = deepSeekClient.chat(messages, model, false);
                if (response == null || response.isBlank()) return null;
                // Never publish or remember an answer generated with a replaced persona.
                if (!boundary.equals(configuration.contentBoundary())) return null;
                if (assigned == null ? !persona.equals(configuration.systemPrompt(false))
                        : !assigned.equals(configuration.groupPersona(scope[1],scope[3]))) return null;
                response = limit(response.strip());
                memory.remember(owner, input, response, () -> deepSeekClient.isConfigured() && configuration.settings().aiEnabled());
                return response;
            } catch (Exception error) {
                log.warn("AI 聊天失败，跳过回复", error);
                return null;
            }
        }
    }

    public void clearHistory(String userId) { memory.clear(ConversationMemoryService.owner(userId)); }
    public int getHistorySize(String userId) {
        String owner = ConversationMemoryService.owner(userId);
        return memory.size(owner) == 0 ? 0 : 1 + memory.context(owner, 50, 4000, 4000).size();
    }
    private static String limit(String text) { return text.length() > 4000 ? text.substring(0, 4000) : text; }
}
