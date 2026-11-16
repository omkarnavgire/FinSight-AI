-- V6__cash_flow_snapshots_table.sql  |  Module: cashflow
-- Creates: cash_flow_snapshots
-- Cross-module logical FK: cash_flow_snapshots.user_id -> users.id (enforced in CashFlowService)

CREATE TABLE cash_flow_snapshots (
    id                 BIGINT          NOT NULL AUTO_INCREMENT,
    -- Logical FK: cash_flow_snapshots.user_id -> users.id  (cross-module; enforced in code)
    user_id            BIGINT          NOT NULL,
    generated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    horizon_days       TINYINT         NOT NULL COMMENT '30, 60, or 90',
    low_confidence     BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'true when fewer than 30 days of history',
    confidence_reason  VARCHAR(50)     NULL COMMENT 'NULL or NO_HISTORY',
    forecast_json      JSON            NOT NULL COMMENT 'Array of {date, projected_balance}',
    PRIMARY KEY (id),
    INDEX idx_cashflow_user_horizon (user_id, horizon_days),
    INDEX idx_cashflow_generated_at (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
