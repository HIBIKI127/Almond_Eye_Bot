package org.almond_eye_bot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

@Slf4j
@Service
public class WebMessageService {
    @Autowired private BotConfigurationService configuration;
    @Autowired private BotReplyService replies;

    public Map<String, Object> handleMessage(String userId, String content) {
        try (var scope = replies.afterResponse()) { return handleMessageNow(userId,content); }
    }

    private Map<String, Object> handleMessageNow(String userId, String content) {
        if (content == null || content.isBlank()) return Map.of("success", false, "error", "消息不能为空");
        try {
            var context = new BotReplyService.Context("web:" + userId, null, null, userId, false, false, false);
            var output = replies.respond(content.strip(), context);
            if (output.isEmpty()) return Map.of("success", true, "type", "none");
            Map<String, Object> result = new HashMap<>(webOutput(output.get(0)));
            result.put("success", true);
            result.put("messages", output.stream().map(WebMessageService::webOutput).toList());
            return result;
        } catch (Exception error) {
            log.warn("Web 消息处理失败，跳过回复", error);
            return Map.of("success", true, "type", "none");
        }
    }

    private static Map<String, Object> webOutput(BotReplyService.Output reply) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", reply.imageBase64() == null ? "text" : "image");
        result.put("content", reply.text() == null ? "" : reply.text());
        result.put("parts", reply.singleMessage()
                ? (reply.text() == null || reply.text().isBlank() ? List.of() : List.of(reply.text()))
                : ReplyTextSplitter.split(reply.text()));
        if (reply.imageBase64() != null) result.put("imageBase64", reply.imageBase64());
        return result;
    }
}
