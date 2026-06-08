-- Argus Postgres schema (SQL side: users, cases, audit, policies, sanctions, tx graph).
-- NOTE: The Spring services use Hibernate ddl-auto=update and will create/upgrade
-- these tables automatically. This file documents the canonical schema and seeds
-- data for a `docker compose up` Postgres-only workflow. Tables use IF NOT EXISTS
-- so it is safe to run alongside Hibernate.

CREATE TABLE IF NOT EXISTS user_account (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(255)       NOT NULL,
    role          VARCHAR(32)        NOT NULL,
    enabled       BOOLEAN            NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP          NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sanctioned_address (
    address     VARCHAR(64) PRIMARY KEY,
    entity      VARCHAR(255) NOT NULL,
    list_source VARCHAR(64)  NOT NULL,
    program     VARCHAR(64)  NOT NULL,
    severity    INT          NOT NULL
);

CREATE TABLE IF NOT EXISTS transaction_edge (
    id           BIGSERIAL PRIMARY KEY,
    from_address VARCHAR(64)      NOT NULL,
    to_address   VARCHAR(64)      NOT NULL,
    amount_usd   DOUBLE PRECISION NOT NULL,
    asset        VARCHAR(32)      NOT NULL,
    tx_hash      VARCHAR(80)      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_edge_from ON transaction_edge (from_address);
CREATE INDEX IF NOT EXISTS idx_edge_to ON transaction_edge (to_address);

CREATE TABLE IF NOT EXISTS tool_status (
    tool_id     VARCHAR(64) PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS screening_policy (
    policy_key  VARCHAR(64) PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    int_value   INT          NOT NULL
);

CREATE TABLE IF NOT EXISTS case_record (
    id                VARCHAR(64) PRIMARY KEY,
    subject_address   VARCHAR(64) NOT NULL,
    decision          VARCHAR(16) NOT NULL,
    risk_score        INT         NOT NULL,
    risk_band         VARCHAR(16) NOT NULL,
    summary           TEXT        NOT NULL,
    risk_factors_json TEXT,
    created_by        VARCHAR(64),
    created_at        TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGSERIAL PRIMARY KEY,
    actor      VARCHAR(64)  NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    target     VARCHAR(128),
    detail     TEXT,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);
