-- V2__auth_tables.sql  |  Module: auth
-- Creates: users, refresh_tokens

CREATE TABLE users (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    email          VARCHAR(255)   NOT NULL,
    password_hash  VARCHAR(255)   NOT NULL,
    full_name      VARCHAR(100)   NOT NULL,
    role           ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
    created_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refresh_tokens (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    -- Same-module FK: refresh_tokens.user_id -> users.id
    -- Both tables are owned by the auth module; a real FOREIGN KEY constraint is declared below.
    user_id            BIGINT         NOT NULL,
    token_hash         VARCHAR(64)    NOT NULL COMMENT 'SHA-256 hex of raw 256-bit token; raw value never stored',
    expires_at         DATETIME       NOT NULL,
    revoked            BOOLEAN        NOT NULL DEFAULT FALSE,
    revocation_reason  ENUM('ROTATED','LOGOUT','REUSE_DETECTED') NULL DEFAULT NULL,
    created_at         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_refresh_token_hash (token_hash),
    INDEX idx_refresh_tokens_user_revoked (user_id, revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
