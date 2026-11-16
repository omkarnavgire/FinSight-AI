# FinSight Foundation — Design

## Module Map

```
com.finsight
├── auth/
│   ├── domain/        User, Role, RefreshToken
│   ├── repository/    UserRepository, RefreshTokenRepository
│   ├── service/       AuthService, JwtService, TokenService, RateLimiterService
│   ├── controller/    AuthController  (/api/v1/auth/*)
│   └── dto/           RegisterRequest, LoginRequest, TokenResponse
│
├── accounts/
│   ├── domain/        Account, AccountType (CHECKING, SAVINGS, CREDIT)
│   ├── repository/    AccountRepository
│   ├── service/       AccountService
│   ├── controller/    AccountController  (/api/v1/accounts/*)
│   └── dto/           AccountDto, CreateAccountRequest
│
├── transactions/
│   ├── domain/        Transaction, Category (enum)
│   ├── repository/    TransactionRepository
│   ├── service/       TransactionService, CategoryResolver
│   ├── controller/    TransactionController  (/api/v1/transactions/*)
│   └── dto/           TransactionDto, CreateTransactionRequest, TransactionFilter
│
├── budgeting/
│   ├── domain/        Budget, BudgetAlert
│   ├── repository/    BudgetRepository, BudgetAlertRepository
│   ├── service/       BudgetService, BudgetAlertService
│   ├── controller/    BudgetController  (/api/v1/budgets/*)
│   └── dto/           BudgetDto, BudgetProgressDto
│
├── analytics/
│   ├── service/       AnalyticsService
│   ├── controller/    AnalyticsController  (/api/v1/analytics/*)
│   └── dto/           CategoryBreakdownDto, MonthlyTrendDto
│
├── cashflow/
│   ├── domain/        CashFlowSnapshot
│   ├── repository/    CashFlowSnapshotRepository
│   ├── service/       CashFlowService, RecurringPatternDetector
│   ├── scheduler/     CashFlowScheduler  (@Scheduled nightly 02:00, batch size 100)
│   ├── controller/    CashFlowController  (/api/v1/cashflow/*)
│   └── dto/           ForecastDto, ForecastDayDto
│                      ForecastDto carries: lowConfidence (boolean), reason (nullable string)
│
├── fraud/
│   ├── domain/        FraudAlert, FraudRule (sealed interface)
│   ├── repository/    FraudAlertRepository
│   ├── service/       FraudDetectionService
│   │                  rules: DuplicateTransactionRule
│   │                         AbnormalAmountRule  (fixed threshold: 5 txns, zero-mean guard)
│   │                         UnseenCountryRule   (skips on first-ever transaction)
│   ├── controller/    FraudAlertController  (/api/v1/fraud/alerts/*)
│   └── dto/           FraudAlertDto
│
├── aiinsights/        (post-MVP)
│   ├── service/       AiInsightService, PromptBuilder, OutputValidator
│   ├── controller/    AiInsightController  (/api/v1/ai/insights/*)
│   └── dto/           InsightDto
│
└── shared/
    ├── exception/     FinSightException, AccountNotFoundException,
    │                  TransactionNotFoundException, UnauthorisedException
    ├── advice/        GlobalExceptionHandler (@RestControllerAdvice)
    ├── security/      JwtAuthFilter, SecurityConfig
    ├── country/       CountryCodes  (ISO 3166-1 alpha-2 allowlist)
    ├── pagination/    PageResponse<T>
    └── seed/          DataSeeder (dev profile only)
```

---

## Database Schema (Flyway migrations)

### `users`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| email | VARCHAR(255) UNIQUE NOT NULL | |
| password_hash | VARCHAR(255) NOT NULL | BCrypt strength 12 |
| full_name | VARCHAR(100) NOT NULL | |
| role | ENUM('USER','ADMIN') NOT NULL | default USER |
| created_at | DATETIME NOT NULL | |
| updated_at | DATETIME NOT NULL | |

### `refresh_tokens`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| user_id | BIGINT FK → users.id | |
| token_hash | VARCHAR(64) NOT NULL | SHA-256 hex of raw 256-bit token |
| expires_at | DATETIME NOT NULL | now + 7 days |
| revoked | BOOLEAN NOT NULL | default false |
| revocation_reason | ENUM('ROTATED','LOGOUT','REUSE_DETECTED') | NULL when not revoked |
| created_at | DATETIME NOT NULL | |

Index: `(token_hash)` — lookup on every refresh call.
Index: `(user_id, revoked)` — bulk revocation on re-use detection.

> Raw token is never stored. On `/api/v1/auth/refresh`, the incoming raw token is hashed and matched against `token_hash`. Before writing, the row is locked with `SELECT ... FOR UPDATE` so two concurrent requests cannot both succeed with the same token. A successful match issues a new token (rotation: old token marked `revoked=true, revocation_reason='ROTATED'`) in a single `@Transactional` block. If the token is already `revoked` with reason `ROTATED`, re-use detection fires: every currently active refresh token for that user is set to `revoked=true, revocation_reason='REUSE_DETECTED'` and the request returns `401`. If the token is `revoked` with reason `LOGOUT` or `REUSE_DETECTED`, only `401` is returned — no further re-use detection, no other sessions affected.

**Revocation reason semantics**

| Scenario | revoked | revocation_reason | Re-use detection? |
|----------|---------|-------------------|-------------------|
| Active token | false | NULL | n/a |
| Successfully rotated | true | ROTATED | Yes — if re-presented, all active tokens → REUSE_DETECTED |
| Logged out | true | LOGOUT | No |
| All-tokens bulk revocation (re-use event) | true | REUSE_DETECTED | No — returns 401 only; no further cascade |
| Expired (past expires_at) | either | either | No — just 401 |

**Concurrent rotation locking**

`TokenService.rotate()` acquires a row-level lock on the `refresh_tokens` row before reading `revoked`:

```
BEGIN TRANSACTION
  SELECT * FROM refresh_tokens WHERE token_hash = :hash FOR UPDATE
  -- if revoked=true → check reason, return 401 (or trigger re-use detection)
  -- if revoked=false and not expired → proceed with rotation
  INSERT new refresh_token row
  UPDATE old row: SET revoked=true, revocation_reason='ROTATED'
COMMIT
```

Two concurrent requests with the same token hash compete for the `FOR UPDATE` lock. The first to acquire it completes the rotation. The second, when it acquires the lock, finds `revoked=true, revocation_reason='ROTATED'` and triggers re-use detection.

**Acceptance criteria**
- AC-TOK-1: A successful `/api/v1/auth/refresh` call issues a new token and sets `revoked=true, revocation_reason='ROTATED'` on the old one within a single DB transaction; if either write fails, both roll back.
- AC-TOK-2: Presenting a token with `revoked=true, revocation_reason='ROTATED'` returns `401` and immediately sets every currently active refresh token for that user to `revoked=true, revocation_reason='REUSE_DETECTED'`.
- AC-TOK-2b: Presenting a token with `revoked=true, revocation_reason='REUSE_DETECTED'` returns `401` and does **not** trigger another re-use detection cycle; no further tokens are revoked.
- AC-TOK-3: Presenting a token with `revoked=true, revocation_reason='LOGOUT'` returns `401` and does **not** trigger re-use detection; other sessions are unaffected.
- AC-TOK-4: A refresh token with `expires_at` in the past returns `401` even if `revoked=false`.
- AC-TOK-5: `/api/v1/auth/logout` sets `revoked=true, revocation_reason='LOGOUT'` on the caller's active token and clears the cookie; subsequent use of that token returns `401` without revoking other sessions.
- AC-TOK-6: The `refresh_tokens` table never contains a column value equal to the raw token string; only the SHA-256 hex digest is stored.
- AC-TOK-7: Two concurrent `POST /api/v1/auth/refresh` requests with the same token result in exactly one successful rotation; the second returns `401` and triggers re-use detection.

**Tests**
- `TokenServiceTest`: rotation sets `revocation_reason='ROTATED'`; re-use of ROTATED token sets all active tokens to `revocation_reason='REUSE_DETECTED'` and returns `401`.
- `TokenServiceTest`: re-use of REUSE_DETECTED token returns `401` without triggering another bulk revocation.
- `TokenServiceTest`: re-use of LOGOUT token returns `InvalidTokenException` without calling `revokeAllForUser`.
- `TokenServiceTest`: expired token returns `InvalidTokenException` regardless of `revoked` or `revocation_reason`.
- `AuthControllerIT`: logout → subsequent refresh with same cookie returns `401`; other test-session tokens unaffected.
- `TokenServiceIT` (Testcontainers): two concurrent refresh calls with the same token — exactly one succeeds, one triggers re-use detection.

### `accounts`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| user_id | BIGINT FK → users.id | |
| name | VARCHAR(100) NOT NULL | |
| type | ENUM('CHECKING','SAVINGS','CREDIT') NOT NULL | |
| balance | DECIMAL(15,2) NOT NULL | Current balance = opening_balance + Σ(transaction amounts) |
| opening_balance | DECIMAL(15,2) NOT NULL | Set at account creation; never modified by transaction operations |
| currency | CHAR(3) NOT NULL | default 'USD' |
| active | BOOLEAN NOT NULL | default true |
| created_at | DATETIME NOT NULL | |

All queries include `WHERE user_id = :userId` — ownership enforced in `AccountRepository`.

### `transactions`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| account_id | BIGINT FK → accounts.id | |
| amount | DECIMAL(15,2) NOT NULL | negative = debit |
| category | VARCHAR(50) NOT NULL | auto-assigned; overridable |
| description | VARCHAR(255) | |
| transaction_date | DATE NOT NULL | |
| country_code | CHAR(2) NOT NULL | ISO 3166-1 alpha-2; validated against `CountryCodes` allowlist |
| manual | BOOLEAN NOT NULL | default false |
| created_at | DATETIME NOT NULL | |

Ownership: joined through `accounts.user_id`. All transaction queries join or sub-select on `accounts` to enforce user scope.

> **countryCode is untrusted metadata.** The value is supplied by the client and validated only for format (ISO 3166-1 alpha-2 allowlist). It records the country the client *claims* for the transaction; it does not verify or represent the actual geographic location of the transaction. The unseen-country fraud rule uses it as a behavioural signal — a new code is a reason to flag, not proof of fraud or location. No access-control or location-verification decision may rely on this field alone.

### `budgets`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| user_id | BIGINT FK → users.id | |
| category | VARCHAR(50) NOT NULL | |
| monthly_limit | DECIMAL(15,2) NOT NULL | |
| period_year | SMALLINT NOT NULL | |
| period_month | TINYINT NOT NULL | |
| created_at | DATETIME NOT NULL | |

### `budget_alerts`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| budget_id | BIGINT FK → budgets.id | |
| threshold_pct | TINYINT NOT NULL | 80 or 100 |
| fired_at | DATETIME NOT NULL | |

### `cash_flow_snapshots`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| user_id | BIGINT FK → users.id | |
| generated_at | DATETIME NOT NULL | |
| horizon_days | TINYINT NOT NULL | 30, 60, or 90 |
| low_confidence | BOOLEAN NOT NULL | true when < 30 days history |
| confidence_reason | VARCHAR(50) | NULL or 'NO_HISTORY' |
| forecast_json | JSON NOT NULL | array of {date, projected_balance} |

### `fraud_alerts`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK AUTO_INCREMENT | |
| user_id | BIGINT FK → users.id | |
| transaction_id | BIGINT FK → transactions.id | |
| rule | VARCHAR(50) NOT NULL | DUPLICATE, ABNORMAL_AMOUNT, UNSEEN_COUNTRY |
| acknowledged | BOOLEAN NOT NULL | default false |
| created_at | DATETIME NOT NULL | |

---

## API Endpoints

### Auth  `/api/v1/auth`
| Method | Path | Full route | Auth | Description |
|--------|------|------------|------|-------------|
| POST | `/register` | `/api/v1/auth/register` | ✗ | Register new user |
| POST | `/login` | `/api/v1/auth/login` | ✗ | Login; returns access token (body) + refresh token (HttpOnly cookie) |
| POST | `/refresh` | `/api/v1/auth/refresh` | ✗ (cookie) | Rotate refresh token; returns new access token |
| POST | `/logout` | `/api/v1/auth/logout` | ✓ | Revoke active refresh token; clears cookie |

Cookie set on `/api/v1/auth/login` and `/api/v1/auth/refresh`:

| Attribute | Value | Condition |
|-----------|-------|-----------|
| `HttpOnly` | true | always |
| `Secure` | true | prod and all non-local profiles |
| `Secure` | false | local dev profile only (HTTP without TLS) |
| `SameSite` | Strict | always |
| `Path` | `/api/v1/auth` | always |
| `Max-Age` | 604800 | always |

`AuthController` reads a `security.cookie.secure` boolean property (default `true`; set `false` in `application-dev.yml` only) to set the `Secure` attribute. All other attributes are unconditional.

### Accounts  `/api/v1/accounts`
| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | List authenticated user's accounts |
| POST | `/` | Create account |
| GET | `/{id}` | Get account (404 if not owned by caller) |
| DELETE | `/{id}` | Deactivate account (404 if not owned by caller) |

### Transactions  `/api/v1/transactions`
| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Paginated list (filter: account, date range, category) |
| POST | `/` | Add manual transaction; triggers fraud detection |
| PUT | `/{id}` | Edit manual transaction (404 if not owned) |
| DELETE | `/{id}` | Delete manual transaction (404 if not owned) |
| PATCH | `/{id}/category` | Override category (404 if not owned) |

`countryCode` in `POST /transactions` body is validated against the ISO 3166-1 allowlist; unknown value → `400 Bad Request` before fraud rules run.

### Budgets  `/api/v1/budgets`
| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | List budgets for current period (caller only) |
| POST | `/` | Create budget |
| GET | `/{id}/progress` | Spent vs limit for specified period; `?year=YYYY&month=MM` defaults to current period (404 if not owned) |
| PUT | `/{id}` | Edit budget (404 if not owned) |
| DELETE | `/{id}` | Delete budget (404 if not owned) |

### Analytics  `/api/v1/analytics`
| Method | Path | Description |
|--------|------|-------------|
| GET | `/category-breakdown` | Spending by category for a given month (caller only) |
| GET | `/monthly-trend` | 6-month spend trend (caller only) |
| GET | `/top-categories` | Top 5 categories in a 30-day window (caller only) |

### Cash Flow  `/api/v1/cashflow`
| Method | Path | Description |
|--------|------|-------------|
| GET | `/forecast` | Returns snapshot for requested horizon; `?horizon=30` (default), `?horizon=60`, or `?horizon=90`; includes `lowConfidence` and `reason` fields |
| POST | `/refresh` | Trigger immediate recalculation for all three horizons (rate-limited: 1 per hour per user) |

`POST /refresh` returns `429 Too Many Requests` with `Retry-After` header when the per-user limit is exceeded.

### Fraud  `/api/v1/fraud/alerts`
| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | List active (unacknowledged) fraud alerts for caller |
| PATCH | `/{id}/acknowledge` | Dismiss alert (404 if not owned) |

---

## Security Config

```
/api/v1/auth/register  → permitAll
/api/v1/auth/login     → permitAll  (rate-limited: 5/15 min/IP)
/api/v1/auth/refresh   → permitAll  (cookie-authenticated; see CSRF section below)
/api/v1/auth/logout    → authenticated
/**                    → authenticated
```

Security layer order per request:
```
HTTPS → CORS → Rate-limit filter → Origin/Referer filter (auth endpoints)
     → JwtAuthFilter → @PreAuthorize → Bean Validation
     → Ownership check (service) → Parameterised query → Response sanitisation
```

### CSRF Protection

The primary CSRF defence for cookie-based endpoints (`/api/v1/auth/refresh`, `/api/v1/auth/logout`) is `SameSite=Strict` on the refresh-token cookie — browsers will not attach the cookie on cross-site requests.

A second, independent layer is added via an `OriginRefererFilter` that runs before `JwtAuthFilter` on all `POST /api/v1/auth/*` requests.

#### Origin comparison algorithm

The filter extracts the **effective origin** from the `Origin` header as `scheme + "://" + host + (non-default port ? ":" + port : "")`, normalised to lowercase. It then compares this value against the configured allowed-origins whitelist using **strict equality** — no prefix matching, no path comparison, no wildcard expansion.

Normalisation steps applied before comparison:
1. Parse the value as a URI; reject (→ `403`) if it cannot be parsed.
2. Lowercase the scheme and host.
3. Omit the port if it is the default for the scheme (80 for http, 443 for https).
4. Strip any path, query, or fragment component.

If the `Origin` header is absent, the filter derives an effective origin from the `Referer` header using the same four normalisation steps (extract scheme + host + effective port from the Referer URI; ignore path and beyond). This is **not** prefix matching — the reconstructed origin must match the allowlist exactly.

If both headers are absent on a `POST` to an auth endpoint, the request is rejected with `403 Forbidden`.

`GET` requests and all non-auth endpoints are not subject to this filter (access tokens travel in `Authorization` headers, not cookies).

Malformed values (unparseable URI, missing scheme, missing host) are rejected with `403` before any comparison.

This dual defence means a CSRF attack would need to defeat both `SameSite=Strict` **and** forge a matching origin — infeasible in a standard browser.

If `SameSite` support is ever unavailable (e.g. some older mobile WebViews), the Origin/Referer check remains as the sole layer; this is documented as a known degraded mode.

**Acceptance criteria**
- AC-CSRF-1: A POST to `/api/v1/auth/refresh` with no `Origin` or `Referer` header returns `403`.
- AC-CSRF-2: A POST to `/api/v1/auth/refresh` with an `Origin` not in the allowed list returns `403`.
- AC-CSRF-3: A POST to `/api/v1/auth/refresh` with a valid allowed `Origin` and a valid cookie proceeds normally.
- AC-CSRF-4: The `OriginRefererFilter` does not apply to non-auth endpoints.
- AC-CSRF-5: A malformed or unparseable `Origin` value returns `403`.
- AC-CSRF-6: `Origin: https://evil.example.com` is rejected even if a legitimate origin shares a suffix (no prefix/substring match).
- AC-CSRF-7: `Referer: https://trusted.example.com/path?q=x` is accepted by extracting `https://trusted.example.com` and matching that against the allowlist — the path is not used in the comparison.
- AC-CSRF-8: `Origin: HTTPS://Trusted.Example.Com:443` (mixed case, explicit HTTPS default port) is normalised to `https://trusted.example.com` — scheme and host lowercased, port 443 dropped because it is the HTTPS default — and accepted if that value is in the allowlist.
- AC-CSRF-9: `Origin: HTTP://Trusted.Example.Com:443` is normalised to `http://trusted.example.com:443` — port 443 is **not** the default for HTTP (HTTP default is 80), so it is preserved; this value does not match `https://trusted.example.com` and is rejected with `403`. Scheme is never promoted from `http` to `https` based on port number.

**Normalisation rule (explicit)**

| Input scheme | Input port | Port in normalised origin | Reason |
|-------------|------------|--------------------------|--------|
| `https` | 443 | omitted | default for HTTPS |
| `http` | 80 | omitted | default for HTTP |
| `https` | 8443 | `:8443` preserved | non-default |
| `http` | 443 | `:443` preserved | non-default for HTTP |
| `http` | 8080 | `:8080` preserved | non-default |

Scheme is lowercased and kept as-is — `http` is never converted to `https`.

**Tests**
- `OriginRefererFilterTest`: missing both headers → `403`; disallowed origin → `403`; malformed origin → `403`; suffix-matching attack rejected; allowed origin exact-match passes; Referer with path extracts correct origin; `HTTPS://Trusted.Example.Com:443` normalises to `https://trusted.example.com` and passes (AC-CSRF-8); `HTTP://Trusted.Example.Com:443` normalises to `http://trusted.example.com:443` and is rejected against an allowlist containing `https://trusted.example.com` (AC-CSRF-9).
- `AuthControllerIT`: refresh with spoofed origin returns `403`; refresh with correct origin succeeds.

---

## Account Balance Consistency

The `accounts` table stores two balance fields:

- `opening_balance` — set once at account creation; never changed by transaction operations.
- `balance` — the current balance, defined as: **`opening_balance + Σ(transaction amounts)`**.

`balance` is a denormalised running total kept for read performance. It is always the opening balance plus the sum of all transaction amounts on the account. The opening balance is **not** re-added on each transaction — it was already baked in when the account was created (`balance` is initialised to `opening_balance` at that point).

### Rules

| Operation | balance change | What is not touched |
|-----------|---------------|---------------------|
| Account creation | `balance = opening_balance` (set once) | — |
| Create transaction (amount A) | `balance += A` | `opening_balance` |
| Edit transaction (old A → new A') | `balance += (A' - A)` (net delta only) | `opening_balance` |
| Delete transaction (amount A) | `balance -= A` | `opening_balance` |
| Deactivate account | No change | `opening_balance`, `balance` |

**Duplicate update prevention:** `TransactionService` is the only place that mutates `balance`. No controller, scheduler, or other service calls a balance-update method directly. The edit path computes the net delta `(newAmount - oldAmount)` and applies it in one SQL statement (`UPDATE accounts SET balance = balance + :delta`), so the opening balance is never double-counted.

**Acceptance criteria**
- AC-BAL-1: After account creation with `openingBalance=1000`, `accounts.balance = 1000` and `accounts.opening_balance = 1000`.
- AC-BAL-2: Creating a debit transaction (amount −50) sets `balance = 950`; `opening_balance` remains `1000`.
- AC-BAL-3: Editing that transaction from −50 to −80 sets `balance = 920`; opening balance unchanged.
- AC-BAL-4: Deleting a transaction of −50 restores balance by +50; opening balance unchanged.
- AC-BAL-5: If the transaction INSERT succeeds but the balance UPDATE fails, both roll back.
- AC-BAL-6: Adding a transaction to a deactivated account returns `409 Conflict`; `balance` and `opening_balance` are unchanged.
- AC-BAL-7: `opening_balance` is identical at account creation and after any number of transaction operations.

**Tests**
- `TransactionServiceTest`: create/edit/delete each produce the correct `balance`; `opening_balance` is never modified.
- `TransactionServiceTest`: edit applies net delta once only — no double-application.
- `TransactionServiceTest`: deactivated account rejects new transactions with `AccountDeactivatedException`.
- `TransactionServiceIT` (Testcontainers): simulated balance UPDATE failure rolls back transaction INSERT; `balance` and `opening_balance` unchanged.

---

## Flyway Migration Strategy

The baseline migration (`V1__baseline.sql`) creates the Flyway schema history table only — it does not define any application tables. Each module creates its own tables in a dedicated migration file, ordered by module dependency.

### Migration file ownership

| File | Creates | Module |
|------|---------|--------|
| `V1__baseline.sql` | _(empty; schema marker only)_ | — |
| `V2__auth_tables.sql` | `users`, `refresh_tokens` | `auth` |
| `V3__accounts_table.sql` | `accounts` | `accounts` |
| `V4__transactions_table.sql` | `transactions` | `transactions` |
| `V5__budgets_tables.sql` | `budgets`, `budget_alerts` | `budgeting` |
| `V6__cash_flow_snapshots_table.sql` | `cash_flow_snapshots` | `cashflow` |
| `V7__fraud_alerts_table.sql` | `fraud_alerts` | `fraud` |

**Rules**
- One migration file per module. No table definition appears in more than one file.
- Cross-module logical FK relationships are recorded as SQL comments in the creating module's migration file, not as `FOREIGN KEY` constraints (see FK Policy section).
- Migrations are append-only; existing files are never edited after the first successful run.

**Acceptance criteria**
- AC-FLY-1: A fresh application start runs all migrations in order with no errors.
- AC-FLY-2: No `CREATE TABLE` statement appears in more than one migration file.
- AC-FLY-3: `V1__baseline.sql` contains no `CREATE TABLE` statements.

**Tests**
- `SchemaIT` (Testcontainers): run Flyway against empty MySQL; assert all expected tables exist; assert `V1__baseline.sql` creates no tables.

---

## Transaction Persistence and Fraud-Alert Ordering

Transaction creation follows a strict ordering within a single `@Transactional` method in `TransactionService.create()`:

```
1. Validate countryCode (400 if invalid — before any DB write)
2. Validate account is active (409 if deactivated — before any DB write)
3. Run all FraudRule evaluations synchronously (read-only DB access only)
4. INSERT transaction row
5. UPDATE account balance (balance += amount)
6. INSERT fraud_alert rows for any rules that fired (step 3 results used here)
```

**Rationale for this ordering:**
- Fraud rules run before the insert (step 3) so they can read a consistent pre-insert state.
- The transaction row (step 4) must exist before fraud alerts reference it via `transaction_id` (step 6).
- Steps 4, 5, and 6 are all inside the same `@Transactional` boundary: if any of them fail, all roll back and no partial state is left.
- If fraud evaluation itself throws (e.g. a DB read error in a rule), the entire method rolls back and the transaction is not created.

**Rollback behaviour**

| Failure point | Result |
|---------------|--------|
| countryCode validation (step 1) | 400; nothing written |
| Account deactivated (step 2) | 409; nothing written |
| Fraud rule evaluation throws (step 3) | Full rollback; transaction not created |
| transaction INSERT fails (step 4) | Full rollback |
| balance UPDATE fails (step 5) | Full rollback; transaction row removed |
| fraud_alert INSERT fails (step 6) | Full rollback; transaction row removed, balance unchanged |

**Acceptance criteria**
- AC-ORD-1: Fraud alerts reference a transaction `id` that already exists in `transactions`.
- AC-ORD-2: A failure in fraud-alert creation rolls back the transaction insert and balance update.
- AC-ORD-3: Fraud rules only perform read operations; they never write to the database.

**Tests**
- `TransactionServiceIT`: forced failure on fraud-alert insert rolls back transaction and balance.
- `FraudDetectionServiceTest`: rules are stateless and perform only reads.

---

## In-Process Rate Limiting

`RateLimiterService` is implemented in JVM memory using a sliding-window counter per IP address.

**Limitations (single-instance only)**
- State is not shared across JVM instances. In a multi-instance deployment, each instance has an independent counter — a client can exceed the limit by routing requests to different instances.
- State resets on application restart. A client blocked just before a restart can immediately retry.
- The limiter is intended for the MVP single-instance deployment only.

**Production scaling path**
- When the application scales beyond one instance, `RateLimiterService` must be replaced with a Redis-backed implementation (e.g. using the sliding-window algorithm with `INCR` + `EXPIRE`). The service interface is defined in `shared/security/RateLimiter` to allow this swap without changing call sites.
- This is documented as a post-MVP infrastructure task. The interface is kept stable; only the implementation changes.

**Acceptance criteria**
- AC-RL-1: `RateLimiterService` Javadoc states it is single-instance only and documents the Redis migration path.
- AC-RL-2: The service is exposed through a `RateLimiter` interface so the implementation can be swapped.

**Tests**
- `RateLimiterServiceTest`: 5 attempts pass; 6th returns `false`; counter resets after window expires.

---

This is a Modular Monolith: one deployable unit, modules separated by Java package boundaries. The FK policy follows from that structure.

### Same-module FKs — allowed, enforced at DB level

FKs between tables owned by the **same module** are declared as real MySQL foreign keys. They are safe because one module's service controls both sides.

| FK | Module |
|----|--------|
| `refresh_tokens.user_id → users.id` | `auth` |
| `budget_alerts.budget_id → budgets.id` | `budgeting` |

### Cross-module FKs — declared but not enforced at DB level

FKs that cross module boundaries are **documented in schema comments but not declared as MySQL constraints**. The owning module's service enforces the relationship in code. This keeps modules deployable and testable independently and prevents one module's migration from depending on another module's table.

| Logical relationship | Enforced by |
|----------------------|-------------|
| `accounts.user_id → users.id` | `AccountService` checks user exists before creation |
| `transactions.account_id → accounts.id` | `TransactionService` loads account via `AccountService` interface before creation |
| `cash_flow_snapshots.user_id → users.id` | `CashFlowService` resolves user from `SecurityContext` |
| `fraud_alerts.user_id → users.id` | `FraudDetectionService` takes `userId` from `SecurityContext` |
| `fraud_alerts.transaction_id → transactions.id` | `FraudDetectionService` receives the persisted transaction before creating the alert |

### No cross-module repository access

A module may not import or inject another module's `Repository`. Cross-module reads go through a named service interface in `shared/` or the target module's public API. Violation of this rule is treated as a build error (enforced by ArchUnit test `ModuleBoundaryTest`).

**Acceptance criteria**
- AC-FK-1: Same-module FKs (`refresh_tokens → users`, `budget_alerts → budgets`) are declared as MySQL `FOREIGN KEY` constraints in Flyway migrations.
- AC-FK-2: Cross-module logical relationships are present as schema comments but no `FOREIGN KEY` constraint exists across module boundaries in any migration file.
- AC-FK-3: `ModuleBoundaryTest` (ArchUnit) fails if any module imports another module's `repository` package.

**Tests**
- `ModuleBoundaryTest` (ArchUnit): no cross-module repository imports; each module's `repository` package is accessed only from within that module.
- Migration inspection in `SchemaIT`: verify FK constraint presence for same-module pairs and absence for cross-module pairs.

---

## Ownership Enforcement Pattern

Every service method operating on a user-scoped resource follows this shape:

```
Entity findOwned(Long resourceId, Long userId) {
    return repository.findByIdAndUserId(resourceId, userId)
        .orElseThrow(() -> new ResourceNotFoundException(resourceId));
}
```

- `userId` always comes from `SecurityContextHolder`, never from the request body or path.
- Cross-user access returns `404`, not `403`, to avoid leaking existence.
- All repositories for user-scoped entities expose a `findByIdAndUserId` method.

### Budgets and BudgetAlerts ownership

`budgets` has a direct `user_id` column. All `BudgetRepository` queries that return a single budget include `AND user_id = :userId`. List queries scope by `user_id` only.

`budget_alerts` does **not** have a `user_id` column — it is owned transitively through `budgets`. Ownership is enforced by always joining through `budgets.user_id`:

```sql
-- BudgetAlertRepository pattern
SELECT ba.* FROM budget_alerts ba
JOIN budgets b ON ba.budget_id = b.id
WHERE ba.id = :alertId AND b.user_id = :userId
```

The `BudgetService` method that returns budget progress, and the `BudgetAlertService` method that fires or lists alerts, both resolve the parent `Budget` through `BudgetRepository.findByIdAndUserId` first. No code path reaches `budget_alerts` rows without first confirming the parent `Budget` belongs to the caller.

**Acceptance criteria**
- AC-OWN-B1: A request for budget `id=X` by a user who does not own it returns `404`.
- AC-OWN-B2: A request for budget progress `id=X` by a user who does not own it returns `404`.
- AC-OWN-B3: `BudgetAlertRepository` exposes no query that accepts a bare `alertId` without a `userId` join through `budgets`.
- AC-OWN-B4: `BudgetAlertService` always resolves the parent budget through `BudgetRepository.findByIdAndUserId` before accessing any alert for that budget.

**Tests**
- `BudgetServiceTest`: cross-user access to budget returns `BudgetNotFoundException`.
- `BudgetControllerIT`: user B cannot read, edit, or delete user A's budget (verify `404` on each endpoint).
- `BudgetAlertServiceTest`: alert for a budget not owned by the caller is inaccessible.
- `BudgetControllerIT`: user B cannot view budget progress for user A's budget.

---

## Fraud Rule Edge Cases

| Rule | Normal path | Edge case | Behaviour |
|------|-------------|-----------|-----------|
| Duplicate | Same account + amount + description within 60 s | No prior transactions | Skip rule |
| Abnormal Amount | Amount > 3× 30-day category mean | < 5 transactions in category over last 30 days | **Skip rule** (insufficient baseline) |
| Abnormal Amount | — | Category mean = 0 (all refunds) | Skip rule (division-by-zero guard) |
| Unseen Country | `countryCode` never seen before for this user | First transaction ever | Skip rule |
| Unseen Country | — | Invalid / blank `countryCode` | Reject `400` before rules run |

### Abnormal Amount Rule — fixed skip threshold

**Decision: skip the rule when history is below 5 transactions. This threshold is fixed for the MVP.**

- Fewer than 5 transactions in the same category over the last 30 days → skip (no alert).
- Category mean equals zero → skip (division-by-zero guard).
- The `fraud.abnormal-amount.min-history` configuration property is **removed for MVP**. The value 5 is hardcoded in `AbnormalAmountRule`. Post-MVP, a configurable threshold may be reintroduced, but the skip behaviour on sub-threshold must remain unchanged.
- Rationale: fewer than 5 data points produce a mean too unstable to use as a fraud signal. A false positive early in a user's history erodes trust.

**Acceptance criteria**
- AC-FRAUD-2: Abnormal-amount rule is **skipped** (no alert raised) when fewer than 5 transactions exist in the category over the last 30 days. The transaction is accepted normally.
- AC-FRAUD-2b: Abnormal-amount rule is **skipped** when the category mean is zero.
- AC-FRAUD-2c: There is no configurable threshold property for this rule in the MVP. The constant 5 is defined once in `AbnormalAmountRule` and referenced in its Javadoc.

> **countryCode reminder:** The unseen-country rule uses the client-supplied `countryCode` as a behavioural signal only. It does not verify actual transaction location. See the `transactions` schema note.

---

## Cash-Flow Forecast Edge Cases

| Condition | Behaviour |
|-----------|-----------|
| < 30 days of transaction history | Forecast generated from available data; `lowConfidence: true` |
| Zero transactions | Flat-balance forecast; `lowConfidence: true`, `reason: "NO_HISTORY"` |
| Single-user nightly recalculation failure | Error logged; previous snapshot preserved; scheduler continues to next user |
| Manual refresh within 1 hour | `429` with `Retry-After` header |

---

## Synthetic Data Seed (dev profile only)

`DataSeeder` runs on startup if the `users` table is empty.

**Profile guard:** `DataSeeder` is annotated `@Profile("dev")`. It is not instantiated in any other profile. The demo credentials below must never appear in a production database.

| Item | Detail |
|------|--------|
| Demo user | `demo@finsight.dev` / `Demo@12345` |
| Accounts | Checking ($5,000), Savings ($12,000) |
| Transactions | 180 entries spread over 90 days, all categories, `countryCode` mix of US/GB/CA |
| Budgets | Groceries $600, Dining $300, Transport $200 |
| Fraud alert | 1 × ABNORMAL_AMOUNT on a seeded transaction |
| Cash-flow snapshot | Pre-seeded 30-day forecast so dashboard loads without waiting for scheduler |

**Acceptance criteria**
- AC-SEED-1: `DataSeeder` is annotated `@Profile("dev")` and must not be loaded in `test` or `prod` profiles.
- AC-SEED-2: The demo user `demo@finsight.dev` does not exist in a database initialised under the `prod` profile.
- AC-SEED-3: Starting the application under the `prod` profile against an empty schema results in zero rows in `users` (Flyway runs; DataSeeder does not).

**Tests**
- `DataSeederProfileTest`: verify `DataSeeder` bean is present in a `dev` context and absent in a `prod` context (Spring `@SpringBootTest` with explicit `spring.profiles.active`).
- `DataSeederProfileTest`: `prod` context start against Testcontainers DB leaves `users` table empty after startup.
