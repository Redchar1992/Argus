ALTER TABLE ai_quota_reservation
    ADD COLUMN expires_time DATETIME(3) NULL;

CREATE INDEX idx_ai_quota_reservation_expiry
    ON ai_quota_reservation (status, expires_time);
