-- V5__budgets_tables.sql  |  Module: budgeting
-- Creates: budgets, budget_alerts
-- Cross-module logical FK: budgets.user_id -> users.id (enforced in BudgetService)
-- Same-module FK: budget_alerts.budget_id -> budgets.id (declared below)

CREATE TABLE budgets (
    id             BIGINT          NOT NULL AUTO_INCREMENT,
    -- Logical FK: budgets.user_id -> users.id  (cross-module; enforced in code)
    user_id        BIGINT          NOT NULL,
    category       VARCHAR(50)     NOT NULL,
    monthly_limit  DECIMAL(15,2)   NOT NULL,
    period_year    SMALLINT        NOT NULL,
    period_month   TINYINT         NOT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_budgets_user_id (user_id),
    UNIQUE KEY uq_budget_user_category_period (user_id, category, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE budget_alerts (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    budget_id      BIGINT      NOT NULL,
    threshold_pct  TINYINT     NOT NULL COMMENT '80 or 100',
    fired_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_budget_alerts_budget FOREIGN KEY (budget_id) REFERENCES budgets (id),
    UNIQUE KEY uq_budget_alert_threshold (budget_id, threshold_pct)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
