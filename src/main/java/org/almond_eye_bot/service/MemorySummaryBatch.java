package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSON;
import java.util.*;

final class MemorySummaryBatch {
    private MemorySummaryBatch() {}
    /** Keep complete records, selecting a smaller prefix for unusually long messages. */
    static <T> List<T> first(List<T> records, int limit) {
        int chars = 2, count = 0;
        for (T record : records.subList(0,Math.min(limit,records.size()))) {
            int length = JSON.toJSONString(record).length() + 1;
            if (count >= 2 && chars + length > 48000) break;
            chars += length; count++;
        }
        return List.copyOf(records.subList(0,count));
    }
}
