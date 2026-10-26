package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OneBotMessageService {
    private static final Pattern CQ = Pattern.compile("\\[CQ:([a-zA-Z0-9_]+)((?:,[^]\\r\\n]*)?)\\]");
    private final BotConfigurationService configuration;
    private final BotReplyService replies;
    private final GroupAutoReplyService groupReplies;
    private final GroupMemoryService groupMemory;

    public OneBotMessageService(BotConfigurationService configuration, BotReplyService replies, GroupAutoReplyService groupReplies) {
        this(configuration, replies, groupReplies, null);
    }

    @Autowired
    public OneBotMessageService(BotConfigurationService configuration, BotReplyService replies, GroupAutoReplyService groupReplies,
                               GroupMemoryService groupMemory) {
        this.configuration = configuration; this.replies = replies; this.groupReplies = groupReplies;
        this.groupMemory = groupMemory;
    }

    public void handleEvent(JSONObject event, BiConsumer<String, Map<String, Object>> sender) {
        try (var scope = configuration.summaries().afterResponse()) { handleEventNow(event,sender); }
    }

    private void handleEventNow(JSONObject event, BiConsumer<String, Map<String, Object>> sender) {
        String type = event.getString("message_type");
        String self = event.getString("self_id");
        String user = event.getString("user_id");
        boolean group = "group".equals(type);
        if ((!group && !"private".equals(type)) || self == null || user == null || self.equals(user)) return;
        String target = group ? event.getString("group_id") : user;
        if (target == null) return;
        var policy = group ? configuration.observeGroup(self, target, event.getString("group_name")) : null;
        if (group && (policy == null || !policy.enabled())) return;
        ParsedMessage message = parseMessage(event, self);
        String text = stripMessageEdges(message.text());
        if (group && groupMemory != null) {
            var author = event.getJSONObject("sender");
            String name = author == null ? "" : author.getString("card");
            if ((name == null || name.isBlank()) && author != null) name = author.getString("nickname");
            Long timestamp = event.getLong("time");
            groupMemory.record(self, target, user, name, memoryText(event),
                    timestamp == null ? System.currentTimeMillis() : timestamp * 1000L, false);
        }
        if (text.isEmpty()) return;
        String conversation = "onebot:" + self + ":" + type + ":" + target + (group ? ":" + user : "");
        var context = new BotReplyService.Context(conversation, self, group ? target : null, user, group,
                message.mentioned(), message.otherMention());
        BiConsumer<String, Map<String, Object>> trackedSender = (action, params) -> {
            sender.accept(action, params);
            if (group && groupMemory != null) {
                @SuppressWarnings("unchecked") var segments = (List<Map<String, Object>>) params.get("message");
                StringBuilder sent = new StringBuilder();
                for (var segment : segments) {
                    if ("text".equals(segment.get("type"))) sent.append(((Map<?, ?>) segment.get("data")).get("text"));
                    else if ("image".equals(segment.get("type"))) sent.append("[图片]");
                }
                groupMemory.record(self, target, self, "机器人", sent.toString(), System.currentTimeMillis(), true);
            }
        };
        Reply reply = new Reply(trackedSender, group, target, event.getString("message_id"), message.mentioned(), () -> replies.allowed(context));
        try {
            var outputs = replies.respond(text, context);
            synchronized (OneBotDeliveryLocks.target(self,group,target)) {
                for (var output : outputs) {
                    if (output.text() != null && !output.text().isBlank()) reply.text(output.text(), output.singleMessage());
                    if (output.imageBase64() != null && !output.imageBase64().isBlank()) reply.image(output.imageBase64());
                }
            }
        } catch (Exception error) {
            log.warn("OneBot 消息处理失败，跳过回复", error);
        }
    }

    private record ParsedMessage(String text, boolean mentioned, boolean otherMention) {}

    private String memoryText(JSONObject event) {
        Object raw = event.get("message");
        if (raw instanceof JSONArray segments) {
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                var segment = segments.getJSONObject(i);
                if (segment == null || segment.getJSONObject("data") == null) continue;
                var data = segment.getJSONObject("data");
                String type = segment.getString("type");
                if ("text".equals(type)) content.append(data.getString("text") == null ? "" : data.getString("text"));
                else if ("at".equals(type)) content.append("[提及QQ:").append(data.getString("qq")).append(']');
                else if (!"reply".equals(type)) content.append('[').append(type).append(']');
            }
            return content.toString();
        }
        String content = raw instanceof String ? (String) raw : event.getString("raw_message");
        if (content == null) return "";
        Matcher matcher = CQ.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String placeholder = "reply".equals(matcher.group(1)) ? "" : "[" + matcher.group(1) + "]";
            if ("at".equals(matcher.group(1))) {
                for (String param : matcher.group(2).split(","))
                    if (param.startsWith("qq=")) placeholder = "[提及QQ:" + param.substring(3) + "]";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(result);
        return result.toString().replace("&#91;", "[").replace("&#93;", "]").replace("&amp;", "&");
    }

    private ParsedMessage parseMessage(JSONObject event, String self) {
        Object message = event.get("message");
        if (message instanceof JSONArray segments) {
            StringBuilder text = new StringBuilder();
            boolean mentioned = false;
            boolean otherMention = false;
            for (int i = 0; i < segments.size(); i++) {
                JSONObject segment = segments.getJSONObject(i);
                if (segment == null) continue;
                JSONObject data = segment.getJSONObject("data");
                if (data == null) continue;
                if ("text".equals(segment.getString("type"))) {
                    String value = data.getString("text");
                    if (value != null) text.append(value);
                } else {
                    // Removing a segment must not concatenate separate words into a command.
                    text.append(' ');
                    if ("at".equals(segment.getString("type"))) {
                        if (self.equals(data.getString("qq"))) mentioned = true;
                        else otherMention = true;
                    }
                }
            }
            return new ParsedMessage(text.toString(), mentioned, otherMention);
        }
        String raw = message instanceof String ? (String) message : event.getString("raw_message");
        if (raw == null) return new ParsedMessage("", false, false);
        Matcher cq = CQ.matcher(raw);
        StringBuilder text = new StringBuilder();
        boolean mentioned = false;
        boolean otherMention = false;
        while (cq.find()) {
            if ("at".equals(cq.group(1))) {
                for (String param : cq.group(2).split(",")) {
                    if (param.equals("qq=" + self)) mentioned = true;
                    else if (param.startsWith("qq=")) otherMention = true;
                }
            }
            cq.appendReplacement(text, " ");
        }
        cq.appendTail(text);
        return new ParsedMessage(text.toString().replace("&#91;", "[").replace("&#93;", "]")
                .replace("&amp;", "&"), mentioned, otherMention);
    }

    /** QQ inserts non-breaking spaces and sometimes invisible padding around mentions. */
    private static String stripMessageEdges(String text) {
        int start=0, end=text.length();
        while(start<end && messagePadding(text.codePointAt(start))) start+=Character.charCount(text.codePointAt(start));
        while(end>start && messagePadding(text.codePointBefore(end))) end-=Character.charCount(text.codePointBefore(end));
        return text.substring(start,end);
    }
    private static boolean messagePadding(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp==0x200B || cp==0xFEFF || cp==0x2060;
    }

    private static final class Reply {
        private final BiConsumer<String, Map<String, Object>> sender;
        private final boolean group;
        private final String target, messageId;
        private final BooleanSupplier allowed;
        private boolean quotePending;
        Reply(BiConsumer<String, Map<String, Object>> sender, boolean group, String target,
              String messageId, boolean quote, BooleanSupplier allowed) {
            this.sender = sender; this.group = group; this.target = target;
            this.messageId = messageId; this.quotePending = quote; this.allowed = allowed;
        }
        void text(String text, boolean singleMessage) {
            if (singleMessage) { send(List.of(segment("text", "text", text))); return; }
            // Segment arrays keep AI output literal, even if it contains CQ codes.
            for (String part : ReplyTextSplitter.split(text))
                if (!send(List.of(segment("text", "text", part)))) return;
        }

        void image(String base64) {
            send(List.of(segment("image", "file", "base64://" + base64)));
        }

        private boolean send(List<Map<String, Object>> segments) {
            // Suppress results from slow AI / Wiki calls if the group was disabled meanwhile.
            if (!allowed.getAsBoolean()) return false;
            if (quotePending && messageId != null && !messageId.isBlank()) {
                segments = new ArrayList<>(segments);
                segments.add(0, segment("reply", "id", messageId));
            }
            sender.accept(group ? "send_group_msg" : "send_private_msg",
                    Map.of(group ? "group_id" : "user_id", Long.parseLong(target), "message", segments));
            quotePending = false;
            return true;
        }

        private static Map<String, Object> segment(String type, String key, String value) {
            return Map.of("type", type, "data", Map.of(key, value));
        }
    }
}
