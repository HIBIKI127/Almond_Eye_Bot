package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class WebMessageService {

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private LocalImageService localImageService;

    @Autowired
    private WikiService wikiService;

    public Map<String, Object> handleMessage(String userId, String content) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (content == null || content.trim().isEmpty()) {
                result.put("success", false);
                result.put("error", "消息不能为空");
                return result;
            }

            if (content.startsWith("/")) {
                if (content.startsWith("/AI") || content.startsWith("/AI 对话")) {
                    handleAiChat(userId, content.substring(2).trim(), result);
                } else if (content.equals("清除历史") || content.equals("重置对话")) {
                    handleClearHistory(userId, result);
                } else if (content.startsWith("/FF14Wiki") || content.startsWith("/ff14")) {
                    handleWikiQuery(userId, content.substring(9).trim(), result);
                } else {
                    handleDefaultMessage(result);
                }
            } else if (content.contains("打灰")) {
                handleDahui(result);
            } else if (content.contains("香草马娘")) {
                handleSizhaizhenexin(result);
            } else if (content.contains("随机马娘") || content.contains("随机小马")) {
                handleRandomUmamusume(result);
            } else {
                handleDefaultMessage(result);
            }

        } catch (Exception e) {
            log.error("处理消息失败", e);
            result.put("success", false);
            result.put("error", "服务器错误：" + e.getMessage());
        }

        return result;
    }

    private void handleAiChat(String userId, String userMessage, Map<String, Object> result) {
        if (userMessage.trim().isEmpty()) {
            result.put("success", true);
            result.put("type", "text");
            result.put("content", "💡 使用方式：\nAI 你的问题\n例如：AI 今天天气怎么样？");
            return;
        }

        result.put("success", true);
        result.put("type", "text");
        result.put("thinking", true);

        try {
            String aiResponse = aiChatService.chat(userId, userMessage);
            result.put("thinking", false);
            result.put("content", formatAiResponse(aiResponse));
        } catch (Exception e) {
            result.put("thinking", false);
            result.put("content", "❌ AI 回复失败：" + e.getMessage());
        }
    }

    private void handleClearHistory(String userId, Map<String, Object> result) {
        aiChatService.clearHistory(userId);
        result.put("success", true);
        result.put("type", "text");
        result.put("content", "✅ 已清除聊天历史记录");
    }

    private void handleWikiQuery(String userId, String query, Map<String, Object> result) {
        if (query.trim().isEmpty()) {
            result.put("success", true);
            result.put("type", "text");
            result.put("content", "📚 Wiki 查询使用方式：\nwiki 关键词\n例如：wiki 光之战士");
            return;
        }

        result.put("success", true);
        result.put("type", "text");
        result.put("thinking", true);

        try {
            WikiService.WikiSearchResult wikiResult = wikiService.searchWiki(query, null);
            result.put("thinking", false);

            if (wikiResult != null && wikiResult.content != null) {
                StringBuilder sb = new StringBuilder();
                if (wikiResult.title != null) {
                    sb.append("📖 ").append(wikiResult.title).append("\n\n");
                }
                sb.append(wikiResult.content);
                if (wikiResult.url != null) {
                    sb.append("\n\n🔗 查看更多：").append(wikiResult.url);
                }

                result.put("content", sb.toString());

                if (wikiResult.imageUrls != null && !wikiResult.imageUrls.isEmpty()) {
                    result.put("images", wikiResult.imageUrls);
                }
            } else {
                result.put("content", "未找到相关内容");
            }
        } catch (Exception e) {
            result.put("thinking", false);
            result.put("content", "❌ Wiki 查询失败：" + e.getMessage());
        }
    }

    private void handleDahui(Map<String, Object> result) {
        try {
            String imageBase64 = localImageService.getRandomDahuiImageBase64();
            result.put("success", true);
            result.put("type", "image");
            result.put("imageBase64", imageBase64);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "打灰表情包获取失败~");
        }
    }

    private void handleSizhaizhenexin(Map<String, Object> result) {
        try {
            String imageBase64 = localImageService.getSizhaizhenexinImageBase64();
            result.put("success", true);
            result.put("type", "image");
            result.put("imageBase64", imageBase64);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "死宅真恶心表情包获取失败~");
        }
    }

    private void handleRandomUmamusume(Map<String, Object> result) {
        try {
            String imageBase64 = localImageService.getRandomUmamusumeImageBase64();
            result.put("success", true);
            result.put("type", "image");
            result.put("imageBase64", imageBase64);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "随机马娘图片获取失败~");
        }
    }

    private void handleDefaultMessage(Map<String, Object> result) {
        String helpMsg = "🤖 我是一个 QQ 机器人，您可以：\n" +
                "1. 说\"打灰\"获取表情包\n" +
                "2. 说\"香草马娘\"或\"随机马娘\"获取马娘图片\n" +
                "3. 使用\"/AI+ 问题\"和我聊天（例如：AI 你好）\n" +
                "4. 说\"清除历史\"重置对话记录 \n" +
                "5. 使用\"/FF14Wiki+ 关键词\"查询 Wiki（例如：wiki 光之战士）\n";

        result.put("success", true);
        result.put("type", "text");
        result.put("content", helpMsg);
    }

    private String formatAiResponse(String response) {
        if (response == null) {
            return "抱歉，出现了一些问题。";
        }

        response = response.replaceAll("\\n{3,}", "\n\n");

        if (response.length() > 2000) {
            response = response.substring(0, 1997) + "...";
        }

        return response;
    }
}
