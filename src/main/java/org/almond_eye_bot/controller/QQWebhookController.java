package org.almond_eye_bot.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import org.almond_eye_bot.service.QQMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/qq")
public class QQWebhookController {

    @Value("${qq.app-secret}")
    private String botSecret;

    @Autowired
    private QQMessageService qqMessageService;

    /**
     * ✅ QQ 平台统一回调入口
     */
    @PostMapping("/ask")
    public String webhook(@RequestBody String rawBody) {

        System.out.println("收到QQ请求：" + rawBody);

        JSONObject json = JSON.parseObject(rawBody);

        // ✅ 1. 校验请求（op = 13）
        if (json.getInteger("op") == 13) {
            return handleValidation(json);
        }

        // ✅ 2. 普通消息
        JSONObject d = json.getJSONObject("d");
        if (d == null) {
            return "ok";
        }

        Integer messageType = d.getInteger("message_type");
        if (messageType != null) {
            if (messageType == 0 || messageType == 1) {
                qqMessageService.handle(d);
            }
        }

        return "ok";
    }

    /**
     * ✅ QQ 官方校验逻辑
     */
    private String handleValidation(JSONObject json) {

        JSONObject d = json.getJSONObject("d");
        String plainToken = d.getString("plain_token");
        String eventTs = d.getString("event_ts");

        String signature = sign(eventTs, plainToken);

        return JSON.toJSONString(Map.of(
                "plain_token", plainToken,
                "signature", signature
        ));
    }

    /**
     * ✅ ed25519 签名（官方规则）
     */
    private String sign(String eventTs, String plainToken) {

        try {
            byte[] seed = buildSeed(botSecret);

            EdDSAParameterSpec spec =
                    EdDSANamedCurveTable.getByName("Ed25519");

            EdDSAPrivateKeySpec privSpec =
                    new EdDSAPrivateKeySpec(seed, spec);

            EdDSAPrivateKey privateKey =
                    new EdDSAPrivateKey(privSpec);

            String msg = eventTs + plainToken;
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);

            EdDSAEngine engine = new EdDSAEngine();
            engine.initSign(privateKey);
            engine.update(msgBytes, 0, msgBytes.length);

            return bytesToHex(engine.sign());

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * ✅ 构造 32 字节 seed（官方要求）
     */
    private byte[] buildSeed(String secret) {

        byte[] src = secret.getBytes(StandardCharsets.UTF_8);
        byte[] seed = new byte[32];

        for (int i = 0; i < 32; i++) {
            seed[i] = src[i % src.length];
        }
        return seed;
    }

    /**
     * ✅ byte[] → hex
     */
    private String bytesToHex(byte[] bytes) {

        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @GetMapping("/test")
    public String test() {
        return "ok";
    }
}