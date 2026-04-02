package org.almond_eye_bot.util;

import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HmacUtil {
    public static String sha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
