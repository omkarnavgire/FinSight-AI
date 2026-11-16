-- V3__accounts_table.sql  |  Module: accounts
-- Creates: accounts
-- Cross-module logical FK: accounts.user_id -> users.id (enforced in AccountService)

CREATE TABLE accounts (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    -- Logical FK: accounts.user_id -> users.id  (cross-module; enforced in code)
    user_id          BIGINT          NOT NULL,
    name             VARCHAR(100)    NOT NULL,
    type             ENUM('CHECKING','SAVINGS','CREDIT') NOT NULL,
    opening_balance  DECIMAL(15,2)   NOT NULL COMMENT 'Set once at creation; never modified by transactions',
    balance          DECIMAL(15,2)   NOT NULL COMMENT 'Current balance = opening_balance + SUM(transaction amounts)',
    currency         CHAR(3)         NOT NULL DEFAULT 'USD',
    active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_accounts_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
