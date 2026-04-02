package org.almond_eye_bot.util;

import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class QQSignUtil {
    private static final String TOKEN = "NF2i5t9pewJaySmcKFAbA50Q31pPzsNv";

    public static boolean verify(String signature, String body) {
        if (signature == null || body == null) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TOKEN.getBytes(), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes());
            String calc = Hex.encodeHexString(raw);
            return calc.equalsIgnoreCase(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
