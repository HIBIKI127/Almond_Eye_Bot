package org.almond_eye_bot.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class QQApiClient {
    private static final Logger logger = LoggerFactory.getLogger(QQApiClient.class);

    @Value("${qq.base-url}")
    private String baseUrl;

    @Value("${qq.app-id}")
    private String appId;

    @Value("${qq.app-secret}")
    private String clientSecret;

    private RestTemplate rest = new RestTemplate();

    private String accessToken;

    private long accessTokenExpireTime;

    public void sendPrivateMsg(String userId, String text) {
        sendPrivateMsg(userId, text, null, false);
    }

    public void sendGroupMsg(String groupId, String text) {
        sendGroupMsg(groupId, text, null);
    }

    public void sendPrivateMsgWithReply(String userId, String text, String msgId) {
        sendPrivateMsg(userId, text, msgId, false);
    }

    public void sendGroupMsgWithReply(String groupId, String text, String msgId) {
        sendGroupMsg(groupId, text, msgId);
    }

    public void sendPrivateMsgWakeup(String userId, String text, String eventId) {
        sendPrivateMsg(userId, text, eventId, true);
    }

    public void sendPrivateImage(String userId, String imageBase64) {
        String fileInfo = uploadFileWithBase64(userId, "private", 1, imageBase64, false);
        sendPrivateMsgWithMedia(userId, fileInfo);
    }

    public void sendGroupImage(String groupId, String imageBase64) {
        String fileInfo = uploadFileWithBase64(groupId, "group", 1, imageBase64, false);
        sendGroupMsgWithMedia(groupId, fileInfo);
    }

    private void sendPrivateMsg(String userId, String text, String msgIdOrEventId, boolean isWakeup) {

        JSONObject body = new JSONObject();

        body.put("msg_type", 0);
        body.put("content", text);

        if (msgIdOrEventId != null && !msgIdOrEventId.isEmpty()) {
            if (isWakeup) {
                body.put("event_id", msgIdOrEventId);
                body.put("is_wakeup", true);
            } else {
                body.put("msg_id", msgIdOrEventId);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "QQBot " + getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        logger.info("发送私聊消息到用户：{}", userId);

        ResponseEntity<String> response = rest.exchange(
                baseUrl + "/v2/users/" + userId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        logger.info("发送结果：{}", response.getBody());
    }

    private void sendPrivateMsgWithMedia(String userId, String fileInfo) {

        JSONObject body = new JSONObject();
        body.put("msg_type", 7);

        JSONObject media = new JSONObject();
        media.put("file_info", fileInfo);
        body.put("media", media);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "QQBot " + getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        logger.info("发送私聊图片消息到用户：{}", userId);

        ResponseEntity<String> response = rest.exchange(
                baseUrl + "/v2/users/" + userId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        logger.info("发送结果：{}", response.getBody());
    }

    private void sendGroupMsg(String groupId, String text, String msgId) {

        JSONObject body = new JSONObject();

        body.put("msg_type", 0);
        body.put("content", text);

        if (msgId != null && !msgId.isEmpty()) {
            body.put("msg_id", msgId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "QQBot " + getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        logger.info("发送群聊消息到群组：{}", groupId);

        ResponseEntity<String> response = rest.exchange(
                baseUrl + "/v2/groups/" + groupId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        logger.info("发送结果：{}", response.getBody());
    }

    private void sendGroupMsgWithMedia(String groupId, String fileInfo) {

        JSONObject body = new JSONObject();
        body.put("msg_type", 7);

        JSONObject media = new JSONObject();
        media.put("file_info", fileInfo);
        body.put("media", media);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "QQBot " + getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        logger.info("发送群聊图片消息到群组：{}", groupId);

        ResponseEntity<String> response = rest.exchange(
                baseUrl + "/v2/groups/" + groupId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        logger.info("发送结果：{}", response.getBody());
    }

    private String uploadFile(String targetId, String type, int fileType, String url, boolean srvSendMsg) {

        JSONObject body = new JSONObject();
        body.put("file_type", fileType);
        body.put("url", url);
        body.put("srv_send_msg", srvSendMsg);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "QQBot " + getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        String endpoint;
        if ("private".equals(type)) {
            endpoint = baseUrl + "/v2/users/" + targetId + "/files";
        } else {
            endpoint = baseUrl + "/v2/groups/" + targetId + "/files";
        }

        logger.info("上传文件到：{}, URL: {}", endpoint, url);

        ResponseEntity<String> response = rest.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        JSONObject result = JSON.parseObject(response.getBody());
        String fileInfo = result.getString("file_info");

        if (fileInfo == null || fileInfo.isEmpty()) {
            logger.error("上传文件失败！响应内容：{}", response.getBody());
            throw new RuntimeException("上传文件失败：" + response.getBody());
        }

        logger.info("上传文件成功，file_info: {}", fileInfo);
        return fileInfo;
    }

    private String uploadFileWithBase64(String targetId, String type, int fileType, String base64Data, boolean srvSendMsg) {

        JSONObject body = new JSONObject();
        body.put("file_type", fileType);
        body.put("srv_send_msg", srvSendMsg);
        body.put("file_data", base64Data);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "QQBot " + getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        String endpoint;
        if ("private".equals(type)) {
            endpoint = baseUrl + "/v2/users/" + targetId + "/files";
        } else {
            endpoint = baseUrl + "/v2/groups/" + targetId + "/files";
        }

        logger.info("上传文件到：{}, 使用 base64 数据", endpoint);

        ResponseEntity<String> response = rest.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        JSONObject result = JSON.parseObject(response.getBody());
        String fileInfo = result.getString("file_info");

        if (fileInfo == null || fileInfo.isEmpty()) {
            logger.error("上传文件失败！响应内容：{}", response.getBody());
            throw new RuntimeException("上传文件失败：" + response.getBody());
        }

        logger.info("上传文件成功，file_info: {}", fileInfo);
        return fileInfo;
    }

    private synchronized String getAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpireTime) {
            return accessToken;
        }

        // ✅ 按照官方文档，参数名是 appId 和 clientSecret
        JSONObject req = new JSONObject();
        req.put("appId", appId);
        req.put("clientSecret", clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        logger.info("正在请求 Access Token, appId: {}", appId);

        ResponseEntity<String> res = rest.postForEntity(
                "https://bots.qq.com/app/getAppAccessToken",
                req,
                String.class
        );

        logger.info("获取 Token 响应状态码：{}", res.getStatusCode());
        logger.info("获取 Token 响应内容：{}", res.getBody());

        JSONObject tokenJson = JSON.parseObject(res.getBody());

        // ✅ 检查是否成功
        if (tokenJson.containsKey("access_token")) {
            accessToken = tokenJson.getString("access_token");

            // ✅ expires_in 是 number 类型
            int expiresIn = tokenJson.getIntValue("expires_in", 7200);
            // 提前 60 秒刷新（官方文档说明）
            accessTokenExpireTime = System.currentTimeMillis() + (expiresIn - 60) * 1000L;

            logger.info("获取 Access Token 成功，过期时间：{} 秒", expiresIn);
            return accessToken;
        } else {
            logger.error("获取 Access Token 失败！响应内容：{}", res.getBody());
            throw new RuntimeException("无法获取有效的 Access Token: " + res.getBody());
        }
    }
}
