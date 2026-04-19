--liquibase formatted sql

--changeset demetrius:001-init
CREATE TABLE devices (
    id         UUID        PRIMARY KEY,
    name       TEXT,
    first_seen TIMESTAMPTZ NOT NULL,
    last_seen  TIMESTAMPTZ NOT NULL
);

CREATE TABLE routes (
    id        UUID PRIMARY KEY,
    name      TEXT NOT NULL,
    length_km INT
);

CREATE TABLE sections (
    id       UUID PRIMARY KEY,
    route_id UUID NOT NULL REFERENCES routes(id),
    begin_km INT  NOT NULL,
    end_km   INT  NOT NULL
);

CREATE TABLE reports (
    id          UUID        PRIMARY KEY,
    device_id   UUID        REFERENCES devices(id),
    route_id    UUID        NOT NULL REFERENCES routes(id),
    section_id  UUID        NOT NULL REFERENCES sections(id),
    inspector   TEXT        NOT NULL,
    start_time  TIMESTAMPTZ NOT NULL,
    end_time    TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sites (
    id            UUID             PRIMARY KEY,
    report_id     UUID             NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    position_km   DOUBLE PRECISION,
    lat           DOUBLE PRECISION,
    lng           DOUBLE PRECISION,
    timestamp     TIMESTAMPTZ      NOT NULL,
    validation_id TEXT
);

CREATE TABLE report_fields (
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    spec_id TEXT NOT NULL,
    name    TEXT NOT NULL,
    type    TEXT NOT NULL,
    unit    TEXT,
    value   TEXT,
    PRIMARY KEY (site_id, spec_id)
);

CREATE INDEX idx_reports_start_time ON reports(start_time DESC);
CREATE INDEX idx_reports_route_id   ON reports(route_id);
CREATE INDEX idx_reports_section_id ON reports(section_id);
CREATE INDEX idx_sites_report_id    ON sites(report_id);
CREATE INDEX idx_sections_route_id  ON sections(route_id);
