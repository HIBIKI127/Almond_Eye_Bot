package org.almond_eye_bot.service;

import org.almond_eye_bot.client.WowsStatsClient;
import org.almond_eye_bot.service.WowsStatsData.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class WowsStatsService {
    @Value("${wows-stats.enabled:false}") private boolean enabled = true;
    private final WowsStatsClient client;
    private final WowsStatsRepository repository;
    private final Clock clock;
    private long nextRequest;
    private record Cached(Player player, long time) {}
    private final Map<String,Cached> cache = new LinkedHashMap<>();
    public record Result(String report, String commentary, boolean success) {
        public String combined() { return report + (commentary == null || commentary.isBlank() ? "" : "\n锐评：" + commentary); }
    }
    private static Result notice(String text) { return new Result(text, "", false); }
    @Autowired public WowsStatsService(WowsStatsClient client, WowsStatsRepository repository) {
        this(client, repository, Clock.systemUTC());
    }
    public WowsStatsService(WowsStatsClient client, WowsStatsRepository repository, Clock clock) {
        this.client = client; this.repository = repository; this.clock = clock;
    }

    public String query(String argument) { return queryResult(argument).combined(); }

    public Result queryResult(String argument) {
        Stats data = queryData(argument);
        String source = data.player() == null ? "" : source(data.player(), data.observedTo());
        String header = data.player() == null ? "" : header(data.player());
        if (!data.success()) return notice(header + data.message() + source);
        if (data.requestedDays() == 0) return new Result(header + "账号总体战绩\n所有模式总场次：" + data.player().allBattles()
                + "\n以下为累计随机战（pvp，含单野与组队）：\n" + metrics(data.totals()) + source, roast(data.totals()), true);
        return new Result(header + "近期 " + data.requestedDays() + " 天随机战（累计快照差值）\n实际采集区间："
                + time(data.observedFrom()) + " → " + time(data.observedTo())
                + "\n非逐局日志，起点可能比请求提前最多2小时；更新延迟以官方数据为准。\n"
                + metrics(data.totals()) + source, roast(data.totals()), true);
    }

    /** Shared by the bot renderer and our structured HTTP API; no text parsing or loopback HTTP. */
    public synchronized Stats queryData(String argument) {
        if (!enabled) return failure(Status.DISABLED, "战绩查询已关闭。");
        Command command;
        try { command = Command.parse(argument); }
        catch (IllegalArgumentException invalid) { return failure(Status.INVALID_REQUEST, invalid.getMessage()); }
        if (!client.isConfigured()) return failure(Status.NOT_CONFIGURED, "请先配置 WOWS_STATS_APPLICATION_ID（WG 开发者平台的应用标识），再查询战绩。");
        long now = clock.millis();
        String key = command.region() + ":" + command.player().toLowerCase(Locale.ROOT);
        try {
            var old = cache.get(key);
            Player player;
            long observed;
            if (old != null && now - old.time() >= 0 && now - old.time() < 60_000) {
                player = old.player(); observed = old.time();
            } else {
                if (now < nextRequest) return failure(Status.RATE_LIMITED, "战绩查询太频繁，请稍后再试。");
                nextRequest = now + 3000;
                player = client.player(command.region(), command.player()); observed = clock.millis();
                repository.save(player, observed, true);
                cache.entrySet().removeIf(entry -> now - entry.getValue().time() >= 60_000);
                if (cache.size() >= 200) cache.remove(cache.keySet().iterator().next());
                cache.put(key, new Cached(player, observed));
            }
            long boundary = observed - command.days() * 86_400_000L;
            if (command.days() == 0) return stats(Status.OK, "", command, player, player.random(), 0, 0, observed);
            var baseline = repository.baseline(player.region(), player.id(), boundary);
            if (baseline.isEmpty()) return stats(Status.HISTORY_UNAVAILABLE, "近期 " + command.days() + " 天随机战：历史快照不足。"
                    + "\n已开始记录该账号；官方历史接口已不可用，无法回填过去数据。需要目标起点之前2小时内的本地快照。"
                    + "\n不能把累计战绩或更短区间冒充本次近期战绩；暂无可靠区间数据，不做战绩锐评。", command, player, null, boundary, 0, observed);
            Totals delta;
            try { delta = player.random().minus(baseline.get().random()); }
            catch (IllegalArgumentException reset) { return stats(Status.COUNTER_RESET, "随机战统计发生回退或口径变化，无法可靠计算本次近期战绩，请等待新基线。", command, player, null, boundary, baseline.get().observedAt(), observed); }
            return stats(Status.OK, "", command, player, delta, boundary, baseline.get().observedAt(), observed);
        } catch (IllegalArgumentException expected) { return failure(Status.UPSTREAM_ERROR, expected.getMessage()); }
        catch (Exception failure) { return failure(Status.UPSTREAM_ERROR, "战绩查询或快照保存失败，请稍后再试；本次不会编造战绩。"); }
    }

    private static Stats failure(Status status, String message) {
        return new Stats(status, message, "WG_PUBLIC_API_LOCAL_SNAPSHOTS", "PVP", 0, null, null, 0, 0, 0, null);
    }
    private static Stats stats(Status status, String message, Command command, Player player, Totals totals,
                               long boundary, long from, long to) {
        return new Stats(status, message, "WG_PUBLIC_API_LOCAL_SNAPSHOTS", "PVP", command.days(), player, totals,
                boundary, from, to, totals == null ? null : Metrics.of(totals));
    }
    private static String header(Player player) {
        return player.region().label + " " + player.nickname() + "（" + player.id() + "）\n";
    }
    private static String source(Player player, long observed) {
        return "\n数据：WG 官方 API；采集：" + time(observed) + "\n官方更新时间：" + time(player.updatedAt() * 1000)
                + "\nWoWS Numbers 页面（仅链接，非本次数据源）：https://" + player.region().numbersHost + "/player/"
                + player.id() + "," + URLEncoder.encode(player.nickname(), StandardCharsets.UTF_8) + "/";
    }

    /** Poll only recently requested public accounts; disabling the feature stops all polling. */
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void refresh() {
        if (!enabled || !client.isConfigured()) return;
        for (var account : repository.due(clock.millis())) {
            try {
                repository.markRefreshAttempt(account.region(), account.id(), clock.millis());
                repository.save(client.player(account.region(), Long.toString(account.id())), clock.millis(), false);
            }
            catch (Exception unavailable) { /* Preserve old snapshots; no fabricated updates or chat notifications. */ }
        }
    }

    private static String time(long millis) {
        return millis <= 0 ? "未提供" : Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " 北京时间";
    }
    private static String metrics(Totals t) {
        if (t.battles() == 0) return "随机战场次：0；该统计范围没有可评价的随机战。";
        long deaths = t.battles() - t.survived();
        return String.format(Locale.ROOT, "随机战：%d 场｜胜场：%d｜胜率：%.2f%%\n场均伤害：%.0f｜场均击沉：%.2f\n存活率：%.2f%%｜K/D：%s｜场均经验：%.0f（含高级账号加成）",
                t.battles(), t.wins(), t.winRate(), t.average(t.damage()), t.average(t.frags()),
                t.survived() * 100.0 / t.battles(), deaths == 0 ? "无阵亡，无法计算" : String.format(Locale.ROOT,"%.2f",t.frags()*1.0/deaths), t.average(t.xp()));
    }
    static String roast(Totals t) {
        if (t.battles() == 0) return "零场随机战，炮管还没热，先别急着给自己写海军传奇。";
        if (t.battles() < 20) return "这点样本还没一包薯片扎实，先多打几局，别把一阵顺风当成海神附体。";
        if (t.winRate() < 45) return "这胜率，军舰开出了对面补给舰的效果。先研究小地图和生存，别让主炮只负责给海水加热。";
        if (t.winRate() < 50) return "战绩还在及格线外巡航，炮声挺响，赢面没跟上。少交点无效血量，别把参战当成贡献。";
        if (t.winRate() < 55) return "能打，但离统治海面还差几张小地图。别刚摸过平均线，就把自己摆进名将纪念馆。";
        if (t.winRate() < 60) return "这成绩确实有点东西，可别一顺风就开始摆海军元帅的谱。稳定输出比战后吹炮声更值钱。";
        return "这胜率够狠，对面匹配到你像排到了加班。不过数字再漂亮也不包下局，别把战绩当免交学费的通行证。";
    }
}
