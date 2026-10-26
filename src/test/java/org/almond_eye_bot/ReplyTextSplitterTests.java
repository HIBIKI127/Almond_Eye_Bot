package org.almond_eye_bot;

import org.almond_eye_bot.service.ReplyTextSplitter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReplyTextSplitterTests {
    @Test void separatesSentencesAndLinesButKeepsPunctuationAndClosingQuotes() {
        assertEquals(List.of("你好。", "今天想吃什么？", "双层牛肉堡！", "她说：“不错。”", "那就出发吧"),
                ReplyTextSplitter.split(" 你好。今天想吃什么？双层牛肉堡！\n她说：“不错。”\r\n那就出发吧 "));
        assertEquals(List.of("真的吗？！", "嗯……", "出发。"), ReplyTextSplitter.split("真的吗？！嗯……出发。"));
    }
    @Test void preservesDecimalsEnglishAbbreviationsAndUrls() {
        assertEquals(List.of("Dr. Smith chose version 3.14.", "It works!"), ReplyTextSplitter.split("Dr. Smith chose version 3.14. It works!"));
        assertEquals(List.of("来源：https://example.com/a.b?q=3.14&lang=zh。", "接着聊吧！"),
                ReplyTextSplitter.split("来源：https://example.com/a.b?q=3.14&lang=zh。接着聊吧！"));
        assertEquals(List.of("来源：[官方公告](https://example.com/a.b?q=3.14)。", "下次见！"),
                ReplyTextSplitter.split("来源：[官方公告](https://example.com/a.b?q=3.14)。下次见！"));
    }
    @Test void longSentencesPreferCommasAndNeverSplitSurrogatePairs() {
        String first = "文".repeat(130) + "，";
        String second = "😀".repeat(100) + "。";
        assertEquals(List.of(first, second), ReplyTextSplitter.split(first + second));
        String text = "😀".repeat(450);
        var parts = ReplyTextSplitter.split(text);
        assertEquals(text, String.join("", parts)); assertEquals(3, parts.size());
        for (String part : parts) {
            assertTrue(part.codePointCount(0, part.length()) <= 200);
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }
    @Test void longProtectedLinksAndCodeAreKeptIntact() {
        String url = "https://example.com/" + "x".repeat(300);
        assertEquals(List.of("说明", url + "。", "下一句。"), ReplyTextSplitter.split("说明" + url + "。下一句。"));
        String block = "```java\n" + "a.b();\n".repeat(60) + "```";
        assertTrue(ReplyTextSplitter.split("代码如下：\n" + block + "\n结束。").contains(block));
        assertEquals(List.of("运行 `console.log(1.2);`。", "好了！"), ReplyTextSplitter.split("运行 `console.log(1.2);`。好了！"));
    }
    @Test void emptyRepliesStaySilentAndPathologicalShortSentencesAreBatched() {
        assertTrue(ReplyTextSplitter.split(null).isEmpty()); assertTrue(ReplyTextSplitter.split(" \n\t ").isEmpty());
        String text = "好。".repeat(1000);
        var parts = ReplyTextSplitter.split(text);
        assertTrue(parts.size() <= 20); assertEquals(text, String.join("", parts));
        assertTrue(parts.stream().allMatch(part -> part.length() <= 200));
    }
    @Test void totalReplyLimitIsUnicodeSafe() {
        var parts = ReplyTextSplitter.split("😀".repeat(5000));
        String joined = String.join("", parts);
        assertEquals(4000, joined.codePointCount(0, joined.length())); assertTrue(joined.endsWith("…"));
    }
}
