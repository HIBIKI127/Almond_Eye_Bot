package org.almond_eye_bot.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.almond_eye_bot.service.GoCQMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@RestController
@ConditionalOnProperty(prefix = "gocqhttp", name = "enabled", havingValue = "true")
@RequestMapping("/gocq")
public class GoCQController {

    @Autowired
    private GoCQMessageService goCQMessageService;

    @Value("${gocqhttp.access-token:}")
    private String accessToken;

    private final Set<String> processedMessageIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @PostMapping("/event")
    public String handleEvent(@RequestBody String rawBody,
                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (accessToken.isBlank() || authorization == null || !MessageDigest.isEqual(
                ("Bearer " + accessToken).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        try {
            log.debug("收到Go-CQHTTP事件: {}", rawBody);

            JSONObject json = JSON.parseObject(rawBody);
            String postType = json.getString("post_type");

            if ("meta_event".equals(postType)) {
                return handleMetaEvent(json);
            } else if ("message".equals(postType)) {
                return handleMessageEvent(json);
            } else if ("notice".equals(postType)) {
                return handleNoticeEvent(json);
            }

        } catch (Exception e) {
            log.error("处理Go-CQHTTP事件失败", e);
        }

        return "ok";
    }

    private String handleMetaEvent(JSONObject json) {
        String metaEventType = json.getString("meta_event_type");
        if ("heartbeat".equals(metaEventType)) {
            log.debug("收到心跳包");
        }
        return "ok";
    }

    private String handleMessageEvent(JSONObject json) {
        String messageType = json.getString("message_type");
        Long messageId = json.getLong("message_id");

        String key = json.getString("self_id") + ":" + messageType + ":" + json.getString("group_id")
                + ":" + json.getString("user_id") + ":" + messageId;
        if (processedMessageIds.size() >= 10000) processedMessageIds.clear();
        if (messageId != null && !processedMessageIds.add(key)) {
            log.debug("消息已处理，跳过: {}", messageId);
            return "ok";
        }

        if ("private".equals(messageType)) {
            goCQMessageService.handlePrivateMessage(json);
        } else if ("group".equals(messageType)) {
            goCQMessageService.handleGroupMessage(json);
        }

        return "ok";
    }

    private String handleNoticeEvent(JSONObject json) {
        String noticeType = json.getString("notice_type");
        log.debug("收到通知事件: {}", noticeType);
        return "ok";
    }

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    @PostMapping("/test")
    public String testMessage(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        log.info("测试消息: {}", message);
        return "ok";
    }
}
