package org.almond_eye_bot.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.almond_eye_bot.service.WowsStatsData.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** A separate public-stats cache, backed up with data/, not the bot configuration SQL. */
@Service
public class WowsStatsRepository implements AutoCloseable {
    private static final long DAY = 86_400_000L;
    private final Connection db;
    public WowsStatsRepository(String directory) throws Exception { this(directory, true); }

    @Autowired
    public WowsStatsRepository(@Value("${bot.storage-directory:.}") String directory,
                               @Value("${wows-stats.enabled:false}") boolean enabled) throws Exception {
        if (!enabled) { db = null; return; }
        Path data = Path.of(directory).toAbsolutePath().normalize().resolve("data");
        if (data.toString().contains(";")) throw new IllegalArgumentException("存储目录不能含分号");
        Files.createDirectories(data);
        db = DriverManager.getConnection("jdbc:h2:file:" + data.resolve("wows-player-stats").toString().replace('\\','/')
                + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try (var input = new ClassPathResource("db/wows-stats-schema.sql").getInputStream(); var sql = db.createStatement()) {
            for (String part : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                if (!part.isBlank()) sql.execute(part);
        } catch (Exception error) { db.close(); throw error; }
    }

    public synchronized void save(Player player, long now, boolean requested) {
        try {
            db.setAutoCommit(false);
            try (var watch = db.prepareStatement("MERGE INTO wows_player_watch(region,account_id,last_requested,last_refresh) "
                    + "KEY(region,account_id) VALUES(?,?,?,?)");
                 var snapshot = db.prepareStatement("MERGE INTO wows_player_snapshot "
                    + "(region,account_id,hour_bucket,observed_at,updated_at,battles,wins,damage,frags,survived,xp) "
                    + "KEY(region,account_id,hour_bucket) VALUES(?,?,?,?,?,?,?,?,?,?,?)");
                 var cleanup = db.createStatement()) {
                long lastRequested = now;
                if (!requested) {
                    try (var find = db.prepareStatement("SELECT last_requested FROM wows_player_watch WHERE region=? AND account_id=?")) {
                        find.setString(1, player.region().name()); find.setLong(2, player.id());
                        try (var row = find.executeQuery()) { if (!row.next()) { db.rollback(); return; } lastRequested = row.getLong(1); }
                    }
                }
                watch.setString(1, player.region().name()); watch.setLong(2, player.id());
                watch.setLong(3, lastRequested); watch.setLong(4, now); watch.executeUpdate();
                var t = player.random();
                snapshot.setString(1, player.region().name()); snapshot.setLong(2, player.id());
                snapshot.setLong(3, now / 3_600_000L); snapshot.setLong(4, now); snapshot.setLong(5, player.updatedAt());
                snapshot.setLong(6, t.battles()); snapshot.setLong(7, t.wins()); snapshot.setLong(8, t.damage());
                snapshot.setLong(9, t.frags()); snapshot.setLong(10, t.survived()); snapshot.setLong(11, t.xp()); snapshot.executeUpdate();
                cleanup.executeUpdate("DELETE FROM wows_player_snapshot WHERE observed_at<" + (now - 400 * DAY));
                cleanup.executeUpdate("DELETE FROM wows_player_watch WHERE (region,account_id) IN "
                        + "(SELECT region,account_id FROM wows_player_watch ORDER BY last_requested DESC,region,account_id OFFSET 100 ROWS)");
                db.commit();
            } catch (Exception error) { db.rollback(); throw error; }
            finally { db.setAutoCommit(true); }
        } catch (SQLException error) { throw new IllegalStateException("战绩快照保存失败", error); }
    }

    public synchronized Optional<Snapshot> baseline(Region region, long id, long boundary) {
        try (var query = db.prepareStatement("SELECT * FROM wows_player_snapshot WHERE region=? AND account_id=? "
                + "AND observed_at<=? AND observed_at>=? ORDER BY observed_at DESC LIMIT 1")) {
            query.setString(1, region.name()); query.setLong(2,id); query.setLong(3,boundary); query.setLong(4,boundary - 2 * 3_600_000L);
            try (var row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new Snapshot(row.getLong("observed_at"), row.getLong("updated_at"),
                        new Totals(row.getLong("battles"), row.getLong("wins"), row.getLong("damage"),
                                row.getLong("frags"), row.getLong("survived"), row.getLong("xp"))));
            }
        } catch (SQLException error) { throw new IllegalStateException("战绩快照读取失败", error); }
    }

    public synchronized List<Watched> due(long now) {
        try (var query = db.prepareStatement("SELECT region,account_id FROM wows_player_watch "
                + "WHERE last_requested>=? AND last_refresh<=? ORDER BY last_refresh,region,account_id LIMIT 20")) {
            query.setLong(1, now - 30 * DAY); query.setLong(2, now - 3_600_000L);
            try (var rows = query.executeQuery()) {
                List<Watched> result = new ArrayList<>();
                while (rows.next()) result.add(new Watched(Region.valueOf(rows.getString(1)), rows.getLong(2)));
                return List.copyOf(result);
            }
        } catch (SQLException error) { throw new IllegalStateException("战绩跟踪读取失败", error); }
    }

    public synchronized void markRefreshAttempt(Region region, long id, long now) {
        try (var update = db.prepareStatement("UPDATE wows_player_watch SET last_refresh=? WHERE region=? AND account_id=?")) {
            update.setLong(1,now); update.setString(2,region.name()); update.setLong(3,id); update.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("战绩刷新记录失败", error); }
    }

    @Override @PreDestroy public synchronized void close() throws SQLException { if (db != null) db.close(); }
}
