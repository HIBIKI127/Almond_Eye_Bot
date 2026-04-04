package org.almond_eye_bot.controller;

import org.almond_eye_bot.service.AiChatService;
import org.almond_eye_bot.service.WebMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@CrossOrigin(origins = "*")
@Controller
@RequestMapping("/web")
public class WebChatController {

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private WebMessageService webMessageService;

    @GetMapping("/chat")
    public String chatPage() {
        return "web-chat";
    }

    @PostMapping("/send")
    @ResponseBody
    public Map<String, Object> sendMessage(@RequestBody Map<String, String> request) {
        try {
            String message = request.get("message");
            String userId = request.getOrDefault("userId", generateUserId());

            return webMessageService.handleMessage(userId, message);

        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "服务器错误：" + e.getMessage());
            return response;
        }
    }

    @PostMapping("/clear")
    @ResponseBody
    public Map<String, Object> clearHistory(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String userId = request.get("userId");

            if (userId != null && !userId.isEmpty()) {
                aiChatService.clearHistory(userId);
                response.put("success", true);
                response.put("message", "已清除聊天历史");
            } else {
                response.put("success", false);
                response.put("error", "用户 ID 不能为空");
            }

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "清除失败：" + e.getMessage());
        }

        return response;
    }

    private String generateUserId() {
        return "web_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
