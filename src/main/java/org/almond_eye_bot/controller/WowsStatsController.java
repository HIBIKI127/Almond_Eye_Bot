package org.almond_eye_bot.controller;

import org.almond_eye_bot.service.WowsStatsData.Stats;
import org.almond_eye_bot.service.WowsStatsData.Status;
import org.almond_eye_bot.service.WowsStatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Inherits the existing admin bearer-token/loopback boundary and POST origin check. */
@RestController
@RequestMapping("/admin/api/wows")
public class WowsStatsController {
    private final WowsStatsService stats;
    public WowsStatsController(WowsStatsService stats) { this.stats = stats; }
    public record Input(String region, String player, Integer days) {}

    @PostMapping("/overall")
    public ResponseEntity<Stats> overall(@RequestBody Input input) {
        if (!validIdentity(input) || input.days() != null) return invalid();
        return response(stats.queryData(input.region() + " " + input.player() + " 总体"));
    }

    @PostMapping("/recent")
    public ResponseEntity<Stats> recent(@RequestBody Input input) {
        if (!validIdentity(input) || input.days() == null || input.days() < 1 || input.days() > 365) return invalid();
        return response(stats.queryData(input.region() + " " + input.player() + " 近期 " + input.days()));
    }

    private static boolean validIdentity(Input input) {
        return input != null && input.region() != null && input.region().matches("[^\\s]{1,8}")
                && input.player() != null && input.player().matches("[A-Za-z0-9_]{1,24}");
    }
    private static ResponseEntity<Stats> invalid() {
        return response(new Stats(Status.INVALID_REQUEST,
                "请提供 region（ASIA/EU/NA）和 player（数字ID/完整昵称）；recent另需days（1–365），overall不能带days。",
                "WG_PUBLIC_API_LOCAL_SNAPSHOTS", "PVP", 0, null, null, 0, 0, 0, null));
    }
    private static ResponseEntity<Stats> response(Stats data) {
        int code = switch (data.status()) {
            case OK -> 200;
            case INVALID_REQUEST -> 400;
            case HISTORY_UNAVAILABLE, COUNTER_RESET -> 409;
            case RATE_LIMITED -> 429;
            case DISABLED, NOT_CONFIGURED -> 503;
            case UPSTREAM_ERROR -> 502;
        };
        return ResponseEntity.status(code).body(data);
    }
}
