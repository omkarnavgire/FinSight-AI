-- V4__transactions_table.sql  |  Module: transactions
-- Creates: transactions
-- Cross-module logical FK: transactions.account_id -> accounts.id (enforced in TransactionService)

CREATE TABLE transactions (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    -- Logical FK: transactions.account_id -> accounts.id  (cross-module; enforced in code)
    account_id       BIGINT          NOT NULL,
    amount           DECIMAL(15,2)   NOT NULL COMMENT 'Negative = debit, positive = credit',
    category         VARCHAR(50)     NOT NULL,
    description      VARCHAR(255)    NULL,
    transaction_date DATE            NOT NULL,
    country_code     CHAR(2)         NOT NULL COMMENT 'ISO 3166-1 alpha-2; client-supplied metadata; validated against CountryCodes allowlist',
    manual           BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_transactions_account_id (account_id),
    INDEX idx_transactions_account_date (account_id, transaction_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
