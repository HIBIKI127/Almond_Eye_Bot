package org.almond_eye_bot.config;

public final class BotConfiguration {
    private BotConfiguration() {}

    public record Persona(String prompt) {}
    public record KnowledgeInput(String name, String keywords, boolean enabled) {}
    public record KnowledgeBase(long id, String name, String keywords, boolean enabled) {}
    public record KnowledgeFile(long id, long libraryId, String originalName, String storedName,
                                long byteSize, String createdAt) {}
    public record KnowledgeDocument(KnowledgeFile file, String content) {}
    public record MemoryRecord(String userText, String assistantText, boolean summary) {}
    public record GroupMemoryRecord(String userId, String name, String content, long timestamp,
                                    boolean bot, boolean summary) {}

    public record Settings(Boolean aiEnabled, Boolean wikiEnabled, Boolean builtinImagesEnabled,
                           Boolean keywordRepliesEnabled, Boolean groupRequireAt) {}

    public record RuleInput(String keyword, String matchType, String replyText,
                            Long imageId, Boolean enabled, Integer priority, String action) {
        public RuleInput(String keyword, String matchType, String replyText, Long imageId, Boolean enabled, Integer priority) {
            this(keyword, matchType, replyText, imageId, enabled, priority, "REPLY");
        }
    }

    public record ReplyRule(long id, String keyword, String matchType, String replyText,
                            Long imageId, boolean enabled, int priority, String action) {}

    public record ImageAsset(long id, String originalName, String storedName, String contentType,
                             long byteSize, String createdAt) {}

    public record MatchedReply(String text, String imageBase64, String action, String argument, String keyword) {
        public MatchedReply(String text, String imageBase64) { this(text, imageBase64, "REPLY", "", ""); }
    }

    public record GroupPolicy(String selfId, String groupId, String groupName, boolean enabled,
                              String mode, String keywords, int cooldownSeconds, int dailyLimit,
                              int maxInputChars, int maxOutputTokens, int contextTurns,
                              int followUpSeconds, int callsToday, Double autoReplyRate) {
        public GroupPolicy {
            if (autoReplyRate == null) autoReplyRate = 1.0;
        }
        public GroupPolicy(String selfId, String groupId, String groupName, boolean enabled,
                           String mode, String keywords, int cooldownSeconds, int dailyLimit,
                           int maxInputChars, int maxOutputTokens, int contextTurns,
                           int followUpSeconds, int callsToday) {
            this(selfId, groupId, groupName, enabled, mode, keywords, cooldownSeconds, dailyLimit,
                    maxInputChars, maxOutputTokens, contextTurns, followUpSeconds, callsToday, 1.0);
        }
    }
}
