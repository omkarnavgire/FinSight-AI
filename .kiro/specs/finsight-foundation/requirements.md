# FinSight Foundation — Requirements

## Functional Requirements

### FR-01 Authentication
- FR-01-1: A visitor can register with email, password, and full name.
- FR-01-2: A registered user can log in and receive a JWT access token (15 min, returned in response body) and a refresh token (7 days, returned in an HttpOnly cookie).
- FR-01-3: A user can exchange a valid refresh token for a new access token. Each successful exchange rotates the refresh token: the old token is revoked with reason `ROTATED` and a new one is issued in a single atomic operation. The rotation acquires a row-level lock on the token (`SELECT ... FOR UPDATE`) before writing, so two concurrent refresh requests using the same token cannot both succeed — only the first acquires the lock and completes; the second sees the token already revoked and receives `401`.
- FR-01-4: A user can log out. Logout revokes the active refresh token with reason `LOGOUT` and clears the refresh-token cookie. The access token remains valid until its natural expiry (15 min); no server-side access-token blacklist exists. A `LOGOUT`-revoked token returns `401` but does **not** trigger re-use detection and does **not** revoke any other sessions.
- FR-01-5: Passwords must be at least 8 characters and contain at least one number and one special character.
- FR-01-6: Failed login attempts are rate-limited (5 attempts per 15 minutes per IP). Excess attempts return `429 Too Many Requests` with a `Retry-After` header.
- FR-01-7: If a refresh token that has been revoked with reason `ROTATED` (i.e. a previously valid but now superseded token) is presented, the system treats this as a possible token-theft signal: every currently active refresh token for that user is immediately set to `revoked=true, revocation_reason='REUSE_DETECTED'` and `401 Unauthorized` is returned. The user must log in again. This re-use detection does **not** apply to `LOGOUT`-revoked or `REUSE_DETECTED`-revoked tokens.

### FR-02 Account Management
- FR-02-1: A user can create a synthetic financial account with a name, type (CHECKING, SAVINGS, CREDIT), opening balance, and currency. The opening balance is the starting value and is set once at account creation; it is never modified by transaction operations.
- FR-02-2: A user can view all their accounts and current balances. Current balance is defined as: **Opening Balance + Sum of all transaction amounts on that account**. Transaction amounts are signed: debits are negative, credits are positive.
- FR-02-3: A user can deactivate an account (soft delete). Deactivation sets `active = false`; all data is retained. No new transactions can be added to a deactivated account.
- FR-02-4: On dev-profile startup with an empty database, synthetic seed data is auto-created: 1 demo user, 2 accounts, 180 transactions over 90 days, 3 budgets, 1 fraud alert, 1 cash-flow snapshot.

### FR-03 Transactions
- FR-03-1: A user can view a paginated list of transactions for an account, ordered by `transaction_date` descending.
- FR-03-2: A user can filter transactions by date range, category, and amount range.
- FR-03-3: A user can add a manual transaction (amount, date, category, description, country code). Adding a transaction updates the stored current balance on the account by adding the transaction amount (`balance = balance + amount`) within the same `@Transactional` boundary. The opening balance is not touched.
- FR-03-4: A user can edit a manual transaction's amount, date, category, or description. Editing applies only the net delta to the balance (`balance = balance + (newAmount - oldAmount)`) within the same `@Transactional` block. The old amount's effect is reversed and the new amount's effect is applied exactly once; the opening balance is not touched.
- FR-03-5: A user can delete a manual transaction. Deletion reverses the transaction's contribution to the balance (`balance = balance - amount`) within the same `@Transactional` block. The opening balance is not touched.
- FR-03-6: Transactions cannot be added to, edited on, or deleted from a deactivated account; such attempts return `409 Conflict`.
- FR-03-7: Transactions are categorised automatically on creation (Groceries, Transport, Utilities, Dining, Entertainment, Income, Other).
- FR-03-8: A user can override the auto-assigned category. Category overrides do not affect account balance.

### FR-04 Budgeting
- FR-04-1: A user can create a monthly budget for a category with a spending limit (e.g., Dining: $400). A budget is scoped to a specific year and month.
- FR-04-2: A user can view budget progress for the current period (amount spent vs. monthly limit), computed from transactions in that calendar month.
- FR-04-3: An alert is triggered when spending within a budget's category for the current period reaches 80 % and 100 % of the limit. Each threshold fires at most once per budget per period.
- FR-04-4: A user can edit a budget's monthly limit or delete a budget.
- FR-04-5: A user can retrieve budget performance for any past period by specifying `year` and `month` query parameters on the progress endpoint.

### FR-05 Spending Analytics
- FR-05-1: A user can view a spending breakdown by category for a chosen month.
- FR-05-2: A user can view a month-over-month spending trend for the last 6 months.
- FR-05-3: A user can view their top 5 spending categories for any 30-day window.
- FR-05-4: All analytics are scoped strictly to the authenticated user's data.

### FR-06 Cash-Flow Forecast
- FR-06-1: A user can request a forecast for a 30-day, 60-day, or 90-day horizon from today. The default horizon is 30 days.
- FR-06-2: Forecast is based on recurring income and expenses detected from the last 90 days of transactions. A recurring item is defined as a transaction with the same amount (± 2 %) on approximately the same day of month (± 2 days) occurring at least twice.
- FR-06-3: The forecast snapshot is recalculated nightly at 02:00 for all three horizons.
- FR-06-4: A user can see the projected end-of-period balance for the requested horizon.
- FR-06-5: When fewer than 30 days of transaction history exist, the forecast is generated from available data and the response includes `lowConfidence: true`.
- FR-06-6: When zero transactions exist, the response is a flat-balance forecast (no income, no expenses) with `lowConfidence: true` and `reason: "NO_HISTORY"`.
- FR-06-7: A user can trigger an immediate forecast recalculation. This is rate-limited to once per hour per user; excess requests return `429 Too Many Requests` with a `Retry-After` header.

### FR-07 Fraud Detection
- FR-07-1: The system flags a transaction as suspicious if it is a duplicate of an existing transaction (same account, amount, and description) within 60 seconds.
- FR-07-2: The system flags a transaction if its amount exceeds 3× the user's mean transaction amount for the same category over the last 30 days. This rule is **always skipped** — no alert is raised — if fewer than 5 transactions exist for that category in the last 30 days, or if the category mean is zero (e.g., all prior transactions are refunds). The minimum history threshold is fixed at 5 transactions; it is not configurable for the MVP.
- FR-07-3: The system flags a transaction from a country code that the user has never used before. This rule is skipped on a user's very first transaction. The country code is client-supplied metadata and is used as a behavioural signal only, not as proof of geographic location.
- FR-07-4: A fraud alert is stored and shown as a dashboard notification.
- FR-07-5: A user can dismiss or acknowledge a fraud alert.

---

## Non-Functional Requirements

### NFR-01 Security
- NFR-01-1: All API endpoints require authentication except `/api/v1/auth/register`, `/api/v1/auth/login`, and `/api/v1/auth/refresh`.
- NFR-01-2: HTTPS enforced on all non-local profiles.
- NFR-01-3: Passwords stored as BCrypt hashes (strength 12).
- NFR-01-4: No sensitive data (password, raw token) in API responses or logs.
- NFR-01-5: CORS restricted to known frontend origins per environment.
- NFR-01-6: SQL injection prevention via parameterised queries only.
- NFR-01-7: Refresh tokens are stored as SHA-256 hashes; the raw token value is never persisted.
- NFR-01-8: The `Secure` cookie attribute is profile-dependent. Production and all non-local profiles must set `Secure=true`. The local dev profile may set `Secure=false` to allow HTTP-only development without a TLS certificate. `HttpOnly`, `SameSite=Strict`, `Path=/api/v1/auth`, and `Max-Age=604800` are always set regardless of profile.

### NFR-02 Performance
- NFR-02-1: List endpoints respond in < 300 ms (p95) for up to 10 000 transactions.
- NFR-02-2: Dashboard load (analytics + budget summary) completes in < 500 ms (p95).
- NFR-02-3: Nightly forecast recalculation completes in < 60 seconds for up to 10 000 users.

### NFR-03 Reliability
- NFR-03-1: Application starts cleanly from a fresh database (Flyway runs all migrations).
- NFR-03-2: All `@Transactional` service methods roll back fully on unchecked exceptions. Partial state (e.g. a persisted transaction without a corresponding balance update) must not be possible.
- NFR-03-3: Account balance and transaction persistence are always updated within the same `@Transactional` boundary. There is exactly one balance update per transaction operation (create, edit, delete); duplicate updates and double-counting of the opening balance are not possible. The `accounts.balance` column always reflects: Opening Balance + Sum of all transaction amounts.

### NFR-04 Maintainability
- NFR-04-1: Unit test coverage ≥ 80 % on service classes.
- NFR-04-2: No module may import from another module's internal package (enforced by ArchUnit).
- NFR-04-3: All DB schema changes go through Flyway migration files. The baseline migration (`V1__baseline.sql`) creates only an empty schema marker; every table is created in its own subsequent module-specific migration file. No table definition is duplicated across migrations.

### NFR-05 Accessibility
- NFR-05-1: Frontend meets WCAG 2.1 AA on all primary user flows.
- NFR-05-2: All interactive elements are keyboard-navigable.

### NFR-06 Data
- NFR-06-1: Only synthetic financial data is used in development and CI environments.
- NFR-06-2: Real user data is never committed to source control.

### NFR-07 Rate Limiting
- NFR-07-1: The login rate limiter (`RateLimiterService`) is in-process (JVM memory). It resets on application restart and does not share state across multiple instances.
- NFR-07-2: In a multi-instance deployment (post-MVP), the rate limiter must be replaced with a shared backing store (e.g. Redis). Until then, multi-instance deployments are not supported for rate-limited endpoints.
- NFR-07-3: The in-process rate limiter is documented in `RateLimiterService` Javadoc as a single-instance constraint.
