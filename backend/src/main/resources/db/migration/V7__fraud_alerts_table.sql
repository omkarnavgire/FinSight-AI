-- V7__fraud_alerts_table.sql  |  Module: fraud
-- Creates: fraud_alerts
-- Cross-module logical FK: fraud_alerts.user_id -> users.id (enforced in FraudDetectionService)
-- Cross-module logical FK: fraud_alerts.transaction_id -> financial_transactions.id (enforced in code)

CREATE TABLE fraud_alerts (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    -- Logical FK: fraud_alerts.user_id -> users.id  (cross-module; enforced in code)
    user_id          BIGINT          NOT NULL,
    -- Logical FK: fraud_alerts.transaction_id -> transactions.id (cross-module; enforced in code)
    transaction_id   BIGINT          NOT NULL,
    rule_name        VARCHAR(100)    NOT NULL COMMENT 'DUPLICATE_TRANSACTION | ABNORMAL_AMOUNT | UNSEEN_COUNTRY',
    severity         VARCHAR(20)     NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW | MEDIUM | HIGH',
    description      TEXT            NOT NULL,
    acknowledged     BOOLEAN         NOT NULL DEFAULT FALSE,
    acknowledged_at  DATETIME        NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_fraud_user_id          (user_id),
    INDEX idx_fraud_transaction_id   (transaction_id),
    INDEX idx_fraud_acknowledged     (user_id, acknowledged),
    INDEX idx_fraud_created_at       (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
