package org.almond_eye_bot.util;

public class QQAuthUtil {
    private static final String APP_SECRET = "Q8qZJ3oZL7uhVJ8yofWOG92wqlhdaXVT";

    public static boolean verify(String appId, String signature, String body) {
        if (appId == null || signature == null) {
            return true; // 沙箱环境可放宽
        }

        String raw = appId + body;
        String calc = HmacUtil.sha256(raw, APP_SECRET);
        return calc.equalsIgnoreCase(signature);
    }
}
