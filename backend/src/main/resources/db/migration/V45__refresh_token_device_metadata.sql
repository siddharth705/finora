ALTER TABLE refresh_tokens ADD COLUMN browser VARCHAR(64);
ALTER TABLE refresh_tokens ADD COLUMN device VARCHAR(64);
ALTER TABLE refresh_tokens ADD COLUMN last_seen_ip VARCHAR(64);
ALTER TABLE refresh_tokens ADD COLUMN last_seen_at TIMESTAMPTZ;
