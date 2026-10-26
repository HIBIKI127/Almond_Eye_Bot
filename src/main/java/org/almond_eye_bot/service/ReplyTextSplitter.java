package org.almond_eye_bot.service;

import java.util.*;
import java.util.regex.Pattern;

/** Transport-only splitting: model requests and personal conversation turns stay intact. */
public final class ReplyTextSplitter {
    public static final int MAX_CHARS = 200;
    private static final int MAX_REPLY_CHARS = 4000;
    private static final int MAX_SENTENCE_MESSAGES = 20;
    private static final Pattern PROTECTED = Pattern.compile(
            "```[\\s\\S]*?(?:```|\\z)|`[^`\\r\\n]+`|\\[[^]\\r\\n]+\\]\\(https?://[^\\s]+?\\)|https?://[^\\s<>\\\"。！？；，、‘’“”]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ABBREVIATION = Pattern.compile("(?i).*\\b(?:mr|mrs|ms|dr|prof|sr|jr|vs|etc|e\\.g|i\\.e)\\.$", Pattern.DOTALL);
    private ReplyTextSplitter() {}

    public static List<String> split(String input) {
        if (input == null || input.isBlank()) return List.of();
        String text = input.strip();
        if (text.codePointCount(0, text.length()) > MAX_REPLY_CHARS)
            text = text.substring(0, text.offsetByCodePoints(0, MAX_REPLY_CHARS - 1)) + "…";
        var ranges = protectedRanges(text);
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length();) {
            int protectedEnd = startingRangeEnd(ranges, i);
            if (protectedEnd > i) { i = protectedEnd; continue; }
            int cp = text.codePointAt(i);
            int end = i + Character.charCount(cp);
            boolean boundary = cp == '\n' || cp == '\r' || "。！？!?".indexOf(cp) >= 0
                    || cp == '…' && end < text.length() && text.codePointAt(end) == '…'
                    || cp == '.' && periodEndsSentence(text, start, i);
            if (boundary) {
                while (end < text.length() && "。！？!?.…\"'”’」』）》】".indexOf(text.codePointAt(end)) >= 0)
                    end += Character.charCount(text.codePointAt(end));
                addLongParts(sentences, text.substring(start, end));
                start = end;
            }
            i = end;
        }
        addLongParts(sentences, text.substring(start));
        // Avoid hundreds of notifications for pathological punctuation-heavy model output.
        if (sentences.size() > MAX_SENTENCE_MESSAGES) {
            sentences.clear(); addLongParts(sentences, text);
        }
        return List.copyOf(sentences);
    }

    private static boolean periodEndsSentence(String text, int start, int index) {
        if (index > 0 && index + 1 < text.length()) {
            int previous = text.codePointBefore(index), next = text.codePointAt(index + 1);
            if (Character.isDigit(previous) && Character.isDigit(next)
                    || Character.isLetter(previous) && Character.isLetter(next)) return false;
        }
        String prefix = text.substring(start, index + 1);
        if (ABBREVIATION.matcher(prefix).matches()) return false;
        // Initials such as U.S. remain together.
        return !(index > 0 && Character.isUpperCase(text.charAt(index - 1))
                && (index == start + 1 || index > 1 && !Character.isLetter(text.charAt(index - 2))));
    }

    private static void addLongParts(List<String> output, String text) {
        var ranges = protectedRanges(text);
        for (int start = 0; start < text.length();) {
            int available = text.codePointCount(start, text.length());
            int end = available <= MAX_CHARS ? text.length() : text.offsetByCodePoints(start, MAX_CHARS);
            for (var range : ranges) {
                if (range[0] < end && end < range[1]) { end = range[0] > start ? range[0] : range[1]; break; }
            }
            if (text.codePointCount(start, end) > MAX_CHARS)
                while (end < text.length() && "。！？!?.…\"'”’」』）》】".indexOf(text.codePointAt(end)) >= 0)
                    end += Character.charCount(text.codePointAt(end));
            if (end < text.length() && text.codePointCount(start, end) <= MAX_CHARS) {
                int minimum = text.offsetByCodePoints(start, text.codePointCount(start, end) / 2);
                for (int i = end; i > minimum;) {
                    int cp = text.codePointBefore(i);
                    if ((Character.isWhitespace(cp) || "，,、；;".indexOf(cp) >= 0) && !insideRange(ranges, i - Character.charCount(cp))) {
                        end = i; break;
                    }
                    i -= Character.charCount(cp);
                }
            }
            String part = text.substring(start, end).strip();
            if (!part.isBlank()) output.add(part);
            start = end;
        }
    }

    private static List<int[]> protectedRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        var matcher = PROTECTED.matcher(text);
        while (matcher.find()) {
            int end = matcher.end();
            if (text.regionMatches(true, matcher.start(), "http", 0, 4))
                while (end > matcher.start() && ".,!?;:)]}".indexOf(text.charAt(end - 1)) >= 0) end--;
            ranges.add(new int[]{matcher.start(), end});
        }
        return ranges;
    }
    private static int startingRangeEnd(List<int[]> ranges, int index) {
        for (var range : ranges) if (range[0] == index) return range[1];
        return index;
    }
    private static boolean insideRange(List<int[]> ranges, int index) {
        for (var range : ranges) if (range[0] <= index && index < range[1]) return true;
        return false;
    }
}
