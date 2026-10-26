package org.almond_eye_bot.service;

import java.util.Locale;

public final class WowsStatsData {
    private WowsStatsData() {}
    public enum Region {
        ASIA("亚服", "asia", "asia.wows-numbers.com"),
        EU("欧服", "eu", "wows-numbers.com"),
        NA("美服", "com", "na.wows-numbers.com");
        public final String label, apiDomain, numbersHost;
        Region(String label, String domain, String host) { this.label = label; apiDomain = domain; numbersHost = host; }
        public static Region parse(String text) {
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "亚服", "亚洲", "asia", "as", "sea" -> ASIA;
                case "欧服", "欧洲", "eu" -> EU;
                case "美服", "北美", "na", "us" -> NA;
                default -> throw new IllegalArgumentException("区服仅支持亚服/ASIA、欧服/EU、美服/NA。");
            };
        }
    }
    public record Totals(long battles, long wins, long damage, long frags, long survived, long xp) {
        public Totals {
            if (battles < 0 || wins < 0 || damage < 0 || frags < 0 || survived < 0 || xp < 0 || wins > battles || survived > battles)
                throw new IllegalArgumentException("战绩统计异常，无法生成可靠报告。");
        }
        public Totals minus(Totals older) {
            return new Totals(battles - older.battles, wins - older.wins, damage - older.damage,
                    frags - older.frags, survived - older.survived, xp - older.xp);
        }
        public double winRate() { return battles == 0 ? 0 : wins * 100.0 / battles; }
        public double average(long total) { return battles == 0 ? 0 : total * 1.0 / battles; }
    }
    public record Player(Region region, long id, String nickname, long allBattles, Totals random, long updatedAt) {}
    public record Snapshot(long observedAt, long updatedAt, Totals random) {}
    public record Watched(Region region, long id) {}
    public enum Status { OK, DISABLED, INVALID_REQUEST, NOT_CONFIGURED, RATE_LIMITED, HISTORY_UNAVAILABLE, COUNTER_RESET, UPSTREAM_ERROR }
    /** Window timestamps are Unix milliseconds; Player.updatedAt retains WG's Unix seconds. */
    public record Stats(Status status, String message, String source, String battleType, int requestedDays,
                        Player player, Totals totals, long requestedFrom, long observedFrom, long observedTo,
                        Metrics metrics) {
        public boolean success() { return status == Status.OK; }
    }
    public record Metrics(Double winRate, Double averageDamage, Double averageFrags, Double survivalRate,
                          Double killDeathRatio, Double averageXp) {
        public static Metrics of(Totals t) {
            if (t.battles() == 0) return new Metrics(null, null, null, null, null, null);
            long deaths = t.battles() - t.survived();
            return new Metrics(t.winRate(), t.average(t.damage()), t.average(t.frags()),
                    100.0 * t.survived() / t.battles(), deaths == 0 ? null : 1.0 * t.frags() / deaths, t.average(t.xp()));
        }
    }
    public record Command(Region region, String player, int days) {
        public static Command parse(String text) {
            String[] parts = text == null ? new String[0] : text.strip().split("\\s+");
            if (parts.length < 3 || parts.length > 4) throw new IllegalArgumentException(USAGE);
            Region region = Region.parse(parts[0]);
            String player = parts[1];
            if (!(player.matches("[1-9][0-9]{0,9}") || player.matches("[A-Za-z0-9_]{3,24}")))
                throw new IllegalArgumentException("玩家 ID 请填写数字账号 ID 或完整游戏昵称（3–24个英文字符、数字或下划线）。");
            if (parts.length == 3 && "总体".equals(parts[2])) return new Command(region, player, 0);
            if (parts.length == 4 && "近期".equals(parts[2])) {
                String days = parts[3].replaceFirst("天$", "");
                if (days.matches("[1-9][0-9]{0,2}")) {
                    int count = Integer.parseInt(days);
                    if (count <= 365) return new Command(region, player, count);
                }
            }
            throw new IllegalArgumentException(USAGE);
        }
    }
    public static final String USAGE = "用法：战绩查询 亚服 玩家ID 总体\n或：战绩查询 亚服 玩家ID 近期 7\n支持 ASIA/EU/NA，玩家ID可为数字账号ID或完整昵称；天数1–365。";
}
