package org.almond_eye_bot.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Slf4j
@Component
public class GoCQHttpClient {

    @Value("${gocqhttp.api-url:http://localhost:5700}")
    private String apiUrl;

    @Value("${gocqhttp.access-token:}")
    private String accessToken;

    private final RestTemplate restTemplate;

    public GoCQHttpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        restTemplate = new RestTemplate(factory);
    }

    public void sendPrivateMsg(long userId, String message) {
        try {
            JSONObject response = callApi("send_private_msg", Map.of(
                    "user_id", userId,
                    "message", message
            ));
            if (response != null && response.getIntValue("retcode") != 0) {
                log.warn("发送私聊消息失败: {}", response.getString("msg"));
            }
        } catch (Exception e) {
            log.error("发送私聊消息异常", e);
        }
    }

    public void sendGroupMsg(long groupId, String message) {
        try {
            JSONObject response = callApi("send_group_msg", Map.of(
                    "group_id", groupId,
                    "message", message
            ));
            if (response != null && response.getIntValue("retcode") != 0) {
                log.warn("发送群聊消息失败: {}", response.getString("msg"));
            }
        } catch (Exception e) {
            log.error("发送群聊消息异常", e);
        }
    }

    public void sendPrivateMsgWithReply(long userId, String message, long replyMsgId) {
        String msg = String.format("[CQ:reply,id=%d]%s", replyMsgId, message);
        sendPrivateMsg(userId, msg);
    }

    public void sendGroupMsgWithReply(long groupId, String message, long replyMsgId) {
        String msg = String.format("[CQ:reply,id=%d]%s", replyMsgId, message);
        sendGroupMsg(groupId, msg);
    }

    public void sendPrivateImage(long userId, String imagePath) {
        String msg = String.format("[CQ:image,file=%s]", imagePath);
        sendPrivateMsg(userId, msg);
    }

    public void sendGroupImage(long groupId, String imagePath) {
        String msg = String.format("[CQ:image,file=%s]", imagePath);
        sendGroupMsg(groupId, msg);
    }

    public void sendPrivateImageBase64(long userId, byte[] imageBytes) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String msg = String.format("[CQ:image,file=base64://%s]", base64);
        sendPrivateMsg(userId, msg);
    }

    public void sendGroupImageBase64(long groupId, byte[] imageBytes) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String msg = String.format("[CQ:image,file=base64://%s]", base64);
        sendGroupMsg(groupId, msg);
    }

    public JSONObject getSelfInfo() {
        return callApi("get_login_info", Map.of());
    }

    public JSONObject callApi(String action, Map<String, Object> params) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!accessToken.isBlank()) headers.setBearerAuth(accessToken);

            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(params), headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl.replaceAll("/+$", "") + "/" + action, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject result = JSON.parseObject(response.getBody());
                if (result == null || result.getIntValue("retcode", -1) != 0) {
                    log.warn("OneBot HTTP action {} 返回失败", action);
                }
                return result;
            } else {
                log.warn("API调用失败，状态码: {}", response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("调用Go-CQHTTP API失败: {}", action, e);
            return null;
        }
    }

    public String uploadPrivateImage(long userId, byte[] imageBytes) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("user_id", userId);
            body.add("file", new org.springframework.core.io.ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "image.jpg";
                }
            });

            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl + "/upload_private_image", entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject result = JSON.parseObject(response.getBody());
                if (result.getIntValue("retcode") == 0) {
                    return result.getJSONObject("data").getString("file");
                }
            }
            return null;
        } catch (Exception e) {
            log.error("上传私聊图片失败", e);
            return null;
        }
    }

    public String uploadGroupImage(long groupId, byte[] imageBytes) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("group_id", groupId);
            body.add("file", new org.springframework.core.io.ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "image.jpg";
                }
            });

            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl + "/upload_group_image", entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject result = JSON.parseObject(response.getBody());
                if (result.getIntValue("retcode") == 0) {
                    return result.getJSONObject("data").getString("file");
                }
            }
            return null;
        } catch (Exception e) {
            log.error("上传群聊图片失败", e);
            return null;
        }
    }
}
