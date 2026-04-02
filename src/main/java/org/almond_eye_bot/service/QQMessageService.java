package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.almond_eye_bot.client.QQApiClient;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class QQMessageService {

    @Autowired
    private QQApiClient qqApi;

    @Autowired
    private LocalImageService localImageService;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private WikiService wikiService;

    private final Set<String> processedMessageIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void handle(JSONObject d) {

        String content = d.getString("content");
        String msgId = d.getString("id");
        Integer messageType = d.getInteger("message_type");

        JSONObject author = d.getJSONObject("author");
        String senderOpenid = author.getString("user_openid");

        System.out.println("收到消息 - 类型：" + messageType + ", 发送者：" + senderOpenid + ", 内容：" + content);

        if (msgId != null && !processedMessageIds.add(msgId)) {
            System.out.println("消息已处理，跳过：" + msgId);
            return;
        }

        if (messageType == 0) {
            handleMessage(senderOpenid, content, msgId, "private");
        }

        if (messageType == 1) {
            String groupOpenid = d.getString("group_openid");
            System.out.println("回复群聊消息，群组：" + groupOpenid);
            handleMessage(groupOpenid, content, msgId, "group");
        }
    }

    private void handleMessage(String targetId, String content, String msgId, String type) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        if (content.startsWith("/")){
            if (content.startsWith("/AI对话") || content.startsWith("/AI")) {
                handleAiChatMessage(targetId, content.substring(2).trim(), msgId, type);
            } else if (content.equals("清除历史") || content.equals("重置对话")) {
                handleClearHistory(targetId, msgId, type);
            } else if (content.startsWith("/FF14Wiki") || content.startsWith("/ff14")) {
                handleWikiQueryMessage(targetId, content.substring(9).trim(), msgId, type);
            }
        }

        if (content.contains("打灰")) {
            handleDahuiMessage(targetId, type);
        }
        else if (content.contains("香草马娘")) {
            handleSizhaizhenexinMessage(targetId, type);
        } else if (content.contains("随机马娘") || content.contains("随机小马")) {
            handleRandomUmamusumeMessage(targetId, type);
        } else {
            handleDefaultMessage(targetId, content, msgId, type);
        }
    }

    private void handleAiChatMessage(String targetId, String userMessage, String msgId, String type) {
        if (userMessage.trim().isEmpty()) {
            String reply = "💡 使用方式：\nAI 你的问题\n例如：AI 今天天气怎么样？";
            if ("private".equals(type)) {
                qqApi.sendPrivateMsgWithReply(targetId, reply, msgId);
            } else {
                qqApi.sendGroupMsgWithReply(targetId, reply, msgId);
            }
            return;
        }

        String thinkingPrefix = "🤔 思考中...";
        if ("private".equals(type)) {
            qqApi.sendPrivateMsgWithReply(targetId, thinkingPrefix, msgId);
        } else {
            qqApi.sendGroupMsgWithReply(targetId, thinkingPrefix, msgId);
        }

        try {
            String aiResponse = aiChatService.chat(targetId, userMessage);

            String formattedResponse = formatAiResponse(aiResponse);

            if ("private".equals(type)) {
                qqApi.sendPrivateMsg(targetId, formattedResponse);
            } else {
                qqApi.sendGroupMsg(targetId, formattedResponse);
            }
        } catch (Exception e) {
            String errorMessage = "❌ AI 回复失败：" + e.getMessage();
            if ("private".equals(type)) {
                qqApi.sendPrivateMsg(targetId, errorMessage);
            } else {
                qqApi.sendGroupMsg(targetId, errorMessage);
            }
        }
    }

    private void handleClearHistory(String targetId, String msgId, String type) {
        aiChatService.clearHistory(targetId);

        String successMsg = "✅ 已清除聊天历史记录";
        if ("private".equals(type)) {
            qqApi.sendPrivateMsgWithReply(targetId, successMsg, msgId);
        } else {
            qqApi.sendGroupMsgWithReply(targetId, successMsg, msgId);
        }
    }

    private void handleDefaultMessage(String targetId, String content, String msgId, String type) {
        String helpMsg = "🤖 我是一个 QQ 机器人，您可以：\n" +
                "1. 说\"打灰\"获取表情包\n" +
                "2. 说\"香草马娘\"或\"随机马娘\"获取马娘图片\n" +
                "3. 使用\"/AI+ 问题\"和我聊天（例如：AI 你好）\n" +
                "4. 说\"清除历史\"重置对话记录" +
                "4. 使用\"/FF14Wiki+ 关键词\"查询 Wiki（例如：wiki 光之战士）\n";

        if ("private".equals(type)) {
            qqApi.sendPrivateMsgWithReply(targetId, helpMsg, msgId);
        } else {
            qqApi.sendGroupMsgWithReply(targetId, helpMsg, msgId);
        }
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

    private void handleDahuiMessage(String targetId, String type) {
        try {
            String imageBase64 = localImageService.getRandomDahuiImageBase64();
            String imageName = localImageService.getRandomDahuiImageName();

            System.out.println("发送打灰图片：" + imageName);

            if ("private".equals(type)) {
                qqApi.sendPrivateImage(targetId, imageBase64);
            } else {
                qqApi.sendGroupImage(targetId, imageBase64);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if ("private".equals(type)) {
                qqApi.sendPrivateMsg(targetId, "打灰表情包获取失败~");
            } else {
                qqApi.sendGroupMsg(targetId, "打灰表情包获取失败~");
            }
        }
    }

    private void handleSizhaizhenexinMessage(String targetId, String type) {
        try {
            String imageBase64 = localImageService.getSizhaizhenexinImageBase64();
            String imageName = localImageService.getSizhaizhenexinImageName();

            System.out.println("发送死宅真恶心图片：" + imageName);

            if ("private".equals(type)) {
                qqApi.sendPrivateImage(targetId, imageBase64);
            } else {
                qqApi.sendGroupImage(targetId, imageBase64);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if ("private".equals(type)) {
                qqApi.sendPrivateMsg(targetId, "死宅真恶心表情包获取失败~");
            } else {
                qqApi.sendGroupMsg(targetId, "死宅真恶心表情包获取失败~");
            }
        }
    }

    private void handleRandomUmamusumeMessage(String targetId, String type) {
        try {
            String imageBase64 = localImageService.getRandomUmamusumeImageBase64();

            System.out.println("发送随机马娘图片");

            if ("private".equals(type)) {
                qqApi.sendPrivateImage(targetId, imageBase64);
            } else {
                qqApi.sendGroupImage(targetId, imageBase64);
            }
        } catch (Exception e) {
            System.out.println("随机马娘图片获取失败: " + e.getMessage());

            String errorMessage = "随机马娘图片获取失败~";
            if ("private".equals(type)) {
                qqApi.sendPrivateMsg(targetId, errorMessage);
            } else {
                qqApi.sendGroupMsg(targetId, errorMessage);
            }
        }
    }

    private void handleWikiQueryMessage(String targetId, String query, String msgId, String type) {
        if (query.trim().isEmpty()) {
            String reply = "📚 Wiki 查询使用方式：\nwiki 关键词\n例如：wiki 光之战士";
            if ("private".equals(type)) {
                qqApi.sendPrivateMsgWithReply(targetId, reply, msgId);
            } else {
                qqApi.sendGroupMsgWithReply(targetId, reply, msgId);
            }
            return;
        }

        String thinkingPrefix = "🔍 正在查询 Wiki...";
        if ("private".equals(type)) {
            qqApi.sendPrivateMsgWithReply(targetId, thinkingPrefix, msgId);
        } else {
            qqApi.sendGroupMsgWithReply(targetId, thinkingPrefix, msgId);
        }

        try {
            WikiService.WikiSearchResult result = wikiService.searchWiki(query, null);

            if (result != null && result.content != null) {
                String responseText = formatWikiResponse(result);

                if ("private".equals(type)) {
                    qqApi.sendPrivateMsg(targetId, responseText);
                } else {
                    qqApi.sendGroupMsg(targetId, responseText);
                }

                if (result.imageUrls != null && !result.imageUrls.isEmpty()) {
                    try {
                        Thread.sleep(500);
                        for (String imageUrl : result.imageUrls) {
                            String imageBase64 = downloadAndConvertImage(imageUrl);
                            if (imageBase64 != null) {
                                if ("private".equals(type)) {
                                    qqApi.sendPrivateImage(targetId, imageBase64);
                                } else {
                                    qqApi.sendGroupImage(targetId, imageBase64);
                                }
                                Thread.sleep(300);
                            }
                        }
                    } catch (Exception e) {
                        log.error("发送 Wiki 图片失败", e);
                    }
                }
            }
        } catch (Exception e) {
            String errorMessage = "❌ Wiki 查询失败：" + e.getMessage();
            if ("private".equals(type)) {
                qqApi.sendPrivateMsg(targetId, errorMessage);
            } else {
                qqApi.sendGroupMsg(targetId, errorMessage);
            }
        }
    }

    private String formatWikiResponse(WikiService.WikiSearchResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.title != null) {
            sb.append("📖 ").append(result.title).append("\n\n");
        }

        if (result.content != null) {
            sb.append(result.content);
        }

        if (result.url != null) {
            sb.append("\n\n🔗 查看更多：").append(result.url);
        }

        return sb.toString();
    }

    private String downloadAndConvertImage(String imageUrl) {
        try {
            byte[] imageBytes = Jsoup.connect(imageUrl)
                    .timeout(10000)
                    .userAgent("Mozilla/5.0")
                    .ignoreContentType(true)
                    .execute()
                    .bodyAsBytes();

            String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
            return "base64," + base64;
        } catch (Exception e) {
            log.error("下载图片失败：{}", imageUrl, e);
            return null;
        }
    }
}
