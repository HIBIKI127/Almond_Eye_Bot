package org.almond_eye_bot.service;

import lombok.extern.slf4j.Slf4j;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.function.DoubleSupplier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.*;

/** Executes allowlisted capabilities selected by database rules, independent of transport. */
@Slf4j
@Service
public class BotReplyService {
    public record Context(String conversation, String selfId, String groupId, String userId,
                          boolean group, boolean mentioned, boolean otherMention) {}
    public record Output(String text, String imageBase64, boolean singleMessage) {
        public Output(String text, String imageBase64) { this(text, imageBase64, false); }
    }
    private final AiChatService ai;
    private final LocalImageService images;
    private final WikiService wiki;
    private final BotConfigurationService configuration;
    private final GroupAutoReplyService groupReplies;
    private final Object[] locks = new Object[64];
    private final DoubleSupplier random;
    @Autowired private TagImageService tagImages;
    @Autowired private TwitterSubscriptionService twitter;
    @Autowired private WowsStatsService wowsStats;
    @Autowired private WowsReportRenderer wowsRenderer;

    @Autowired
    public BotReplyService(AiChatService ai, LocalImageService images, WikiService wiki,
                           BotConfigurationService configuration, GroupAutoReplyService groupReplies) {
        this(ai, images, wiki, configuration, groupReplies, () -> ThreadLocalRandom.current().nextDouble());
    }

    public BotReplyService(AiChatService ai, LocalImageService images, WikiService wiki,
                           BotConfigurationService configuration, GroupAutoReplyService groupReplies, DoubleSupplier random) {
        this.ai = ai; this.images = images; this.wiki = wiki; this.configuration = configuration; this.groupReplies = groupReplies;
        this.random = random;
        Arrays.setAll(locks, ignored -> new Object());
    }

    public MemorySummaryQueue.Scope afterResponse() { return configuration.summaries().afterResponse(); }

    public boolean allowed(Context context) {
        if (!context.group()) return true;
        if (context.selfId() == null || context.groupId() == null) return false;
        var group = configuration.group(context.selfId(), context.groupId());
        return group != null && group.enabled();
    }

    /** Matched knowledge libraries take priority, then fixed rules, then normal model conversation. */
    public List<Output> respond(String text, Context context) {
        if (!allowed(context)) return List.of();
        try {
            if (text == null || text.isBlank()) return List.of();
            // Explicit subscription commands bypass probabilistic chatter and AI routing.
            if(twitter!=null){var result=twitter.command(text,context);if(result.isPresent())return List.of(new Output(result.get(),null,true));}
            // Sample once before keyword matching or quota reservation. Mentions and private chats bypass sampling.
            if (context.group() && !context.mentioned()) {
                var policy = configuration.group(context.selfId(), context.groupId());
                if (policy == null || !policy.enabled()) return List.of();
                double rate = policy.autoReplyRate();
                if (rate <= 0 || (rate < 1 && random.getAsDouble() >= rate)) return List.of();
            }
            var matched = configuration.match(text);
            if (matched.isPresent() && (matched.get().action().equals("TAG_IMAGE") || matched.get().action().equals("WOWS_STATS")
                    || !new KnowledgeBaseService(configuration).matches(text))) return execute(matched.get(), context);
            if (!configuration.settings().aiEnabled()) return List.of();
            if (context.group()) return groupReplies.reply(context.selfId(), context.groupId(), context.userId(),
                    text, context.mentioned(), context.otherMention(), false).map(BotReplyService::text).orElseGet(List::of);
            synchronized (lock(context.conversation())) { return text(ai.chat(context.conversation(), text)); }
        } catch (Exception error) {
            log.warn("自动对话失败，跳过回复", error);
            return List.of();
        }
    }

    public List<Output> execute(MatchedReply rule, Context context) {
        if (!allowed(context) || !featureEnabled(rule.action(), configuration.settings())) return List.of();
        try {
            return switch (rule.action()) {
                case "REPLY" -> List.of(new Output(rule.text(), rule.imageBase64()));
                case "AI" -> {
                    if (rule.argument().isBlank()) yield text(rule.text().replace("{keyword}", rule.keyword()));
                    if (context.group()) yield groupReplies.reply(context.selfId(), context.groupId(), context.userId(),
                            rule.argument(), context.mentioned(), context.otherMention(), true).map(BotReplyService::text).orElseGet(List::of);
                    String response;
                    synchronized (lock(context.conversation())) { response = ai.chat(context.conversation(), rule.argument()); }
                    yield text(response);
                }
                case "WIKI" -> {
                    if (rule.argument().isBlank()) yield text(rule.text().replace("{keyword}", rule.keyword()));
                    var result = wiki.searchWiki(rule.argument(), null);
                    if (result == null || result.content == null || result.content.isBlank()) yield List.of();
                    yield text((result.title == null ? "" : result.title + "\n\n") + result.content
                            + (result.url == null ? "" : "\n\n" + result.url));
                }
                case "CLEAR_HISTORY" -> {
                    synchronized (lock(context.conversation())) { ai.clearHistory(context.conversation()); }
                    if (context.group()) groupReplies.clearHistory(context.selfId(), context.groupId(), context.userId());
                    yield text(rule.text());
                }
                case "DAHUI_IMAGE" -> image(images.getRandomDahuiImageBase64());
                case "SIZHAI_IMAGE" -> image(images.getSizhaizhenexinImageBase64());
                case "RANDOM_IMAGE" -> image(images.getRandomUmamusumeImageBase64());
                case "TAG_IMAGE" -> {
                    if (tagImages == null || !tagImages.isEnabled()) yield text("图片检索已关闭。");
                    if (rule.argument().isBlank()) yield text(rule.text().replace("{keyword}", rule.keyword()));
                    String recipient = context.group() ? "group:" + context.selfId() + ":" + context.groupId()
                            : context.selfId() != null ? "private:" + context.selfId() + ":" + context.userId() : context.conversation();
                    var result = tagImages.search(rule.argument(), recipient);
                    yield List.of(new Output(result.text(), result.imageBase64()));
                }
                case "WOWS_STATS" -> {
                    if (rule.argument().isBlank()) yield List.of(new Output(rule.text().replace("{keyword}", rule.keyword()), null, true));
                    var report = wowsStats.queryResult(rule.argument());
                    List<Output> outputs = new ArrayList<>();
                    String image = null;
                    if (report.success() && wowsRenderer != null && wowsRenderer.isEnabled()) {
                        try { image = Base64.getEncoder().encodeToString(wowsRenderer.render(report)); }
                        catch (Exception unavailable) { log.warn("战绩图片生成失败，改用完整文本报告: {}", unavailable.getClass().getSimpleName()); }
                    }
                    outputs.add(new Output(image == null ? report.report() : "", image, true));
                    if (report.commentary() != null && !report.commentary().isBlank())
                        outputs.add(new Output("锐评：" + report.commentary(), null, true));
                    yield List.copyOf(outputs);
                }
                case "HELP" -> {
                    StringJoiner help = new StringJoiner("\n");
                    if (!rule.text().isBlank()) help.add(rule.text());
                    for (var item : configuration.rules()) if (item.enabled() && featureEnabled(item.action(), configuration.settings()))
                        help.add(item.keyword() + (item.action().equals("AI") || item.action().equals("WIKI") || item.action().equals("TAG_IMAGE") || item.action().equals("WOWS_STATS") ? " ＋ 内容" : ""));
                    help.add("推特关注 名称 账号主页链接\n推特获取 名称\n推特取消 名称");
                    yield text(help.toString());
                }
                default -> List.of();
            };
        } catch (Exception error) {
            log.warn("规则动作 {} 执行失败，跳过回复", rule.action(), error);
            return List.of();
        }
    }

    private Object lock(String conversation) { return locks[Math.floorMod(conversation.hashCode(), locks.length)]; }
    private static List<Output> text(String text) { return text == null || text.isBlank() ? List.of() : List.of(new Output(text, null)); }
    private static List<Output> image(String image) { return image == null || image.isBlank() ? List.of() : List.of(new Output("", image)); }
    public static boolean featureEnabled(String action, Settings settings) {
        return switch (action) {
            case "REPLY" -> settings.keywordRepliesEnabled();
            case "AI" -> settings.aiEnabled();
            case "WIKI" -> settings.wikiEnabled();
            case "DAHUI_IMAGE", "SIZHAI_IMAGE", "RANDOM_IMAGE" -> settings.builtinImagesEnabled();
            default -> true;
        };
    }
}
