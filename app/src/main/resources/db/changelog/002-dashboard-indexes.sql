--liquibase formatted sql

--changeset demetrius:002-dashboard-indexes
CREATE INDEX IF NOT EXISTS idx_reports_received_at
    ON reports (received_at DESC);
