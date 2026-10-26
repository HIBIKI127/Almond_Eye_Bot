CREATE TABLE IF NOT EXISTS wows_player_watch (
    region VARCHAR(4) NOT NULL,
    account_id BIGINT NOT NULL,
    last_requested BIGINT NOT NULL,
    last_refresh BIGINT NOT NULL,
    PRIMARY KEY(region,account_id)
);
CREATE TABLE IF NOT EXISTS wows_player_snapshot (
    region VARCHAR(4) NOT NULL,
    account_id BIGINT NOT NULL,
    hour_bucket BIGINT NOT NULL,
    observed_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    battles BIGINT NOT NULL,
    wins BIGINT NOT NULL,
    damage BIGINT NOT NULL,
    frags BIGINT NOT NULL,
    survived BIGINT NOT NULL,
    xp BIGINT NOT NULL,
    PRIMARY KEY(region,account_id,hour_bucket),
    FOREIGN KEY(region,account_id) REFERENCES wows_player_watch(region,account_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS wows_snapshot_time ON wows_player_snapshot(observed_at);
