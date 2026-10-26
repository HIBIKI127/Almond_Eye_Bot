package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.almond_eye_bot.client.QQApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Official QQ transport; all keyword selection is delegated to database rules. */
@Slf4j
@Service
public class QQMessageService {
    @Autowired private QQApiClient qqApi;
    @Autowired private BotConfigurationService configuration;
    @Autowired private BotReplyService replies;
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    public void handle(JSONObject event) {
        try (var scope = replies.afterResponse()) { handleNow(event); }
    }

    private void handleNow(JSONObject event) {
        String messageId = event.getString("id");
        if (messageId != null && !processedMessageIds.add(messageId)) return;
        var author = event.getJSONObject("author");
        if (author == null) return;
        Integer type = event.getInteger("message_type");
        if (Integer.valueOf(0).equals(type))
            handleMessage(author.getString("user_openid"), event.getString("content"), messageId, "private");
        else if (Integer.valueOf(1).equals(type))
            handleMessage(event.getString("group_openid"), event.getString("content"), messageId, "group");
    }

    private void handleMessage(String target, String content, String messageId, String type) {
        if (target == null || content == null || content.isBlank()) return;
        boolean group = "group".equals(type);
        // Official group OpenIDs are not configured OneBot group IDs; never bypass the group allowlist.
        var context = new BotReplyService.Context("official:" + target, null, group ? target : null, target, group, false, false);
        if (!replies.allowed(context)) return;
        try {
            for (var output : replies.respond(content.strip(), context)) {
                if (!replies.allowed(context)) return;
                if (output.text() != null && !output.text().isBlank()) {
                    for (String part : output.singleMessage() ? java.util.List.of(output.text()) : ReplyTextSplitter.split(output.text())) {
                        if (!replies.allowed(context)) return;
                        if (group) qqApi.sendGroupMsg(target, part); else qqApi.sendPrivateMsg(target, part);
                    }
                }
                if (output.imageBase64() != null && !output.imageBase64().isBlank()) {
                    if (!replies.allowed(context)) return;
                    if (group) qqApi.sendGroupImage(target, output.imageBase64()); else qqApi.sendPrivateImage(target, output.imageBase64());
                }
            }
        } catch (Exception error) {
            log.warn("官方 QQ 消息处理失败，跳过回复", error);
        }
    }
}
