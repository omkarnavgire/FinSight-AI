# FinSight Foundation — Tasks

> Status: ⬜ Not started · 🔄 In progress · ✅ Done

---

## Phase 1 — Project Scaffold

| ID | Task | Status |
|----|------|--------|
| T-01 | Initialise Spring Boot 4 project (Maven, Java 25) | ⬜ |
| T-02 | Add core dependencies: Spring Web, Spring Security, Spring Data JPA, Flyway, MySQL driver, Lombok, MapStruct | ⬜ |
| T-03 | Configure `application.yml` profiles: `dev`, `test`, `prod` | ⬜ |
| T-04 | Initialise React + TypeScript frontend with Vite | ⬜ |
| T-05 | Add frontend dependencies: React Query, React Router v7, Axios, Tailwind CSS | ⬜ |
| T-06 | Add Flyway baseline migration `V1__baseline.sql` (empty schema marker only — no table definitions) and module-specific migrations `V2__auth_tables.sql` through `V7__fraud_alerts_table.sql`, one file per module | ⬜ |

---

## Phase 2 — Auth Module

| ID | Task | Status |
|----|------|--------|
| T-07 | ~~Create `users` and `refresh_tokens` Flyway migration (include indexes on `token_hash` and `user_id, revoked`)~~ — covered by T-06 (`V2__auth_tables.sql`); verify indexes are included there | ⬜ |
| T-08 | Implement `User` entity and `UserRepository` | ⬜ |
| T-09 | Implement `JwtService` (issue, validate, parse claims) | ⬜ |
| T-10 | Implement `JwtAuthFilter` and wire into `SecurityConfig` | ⬜ |
| T-11 | Implement `TokenService`: generate 256-bit raw token, store SHA-256 hash, rotate using `SELECT ... FOR UPDATE` lock (issue new + set old `revoked=true, revocation_reason='ROTATED'` in one `@Transactional` block), bulk-revoke on ROTATED re-use detection, reject on LOGOUT re-use without bulk-revoke, reject expired tokens | ⬜ |
| T-11b | Write `TokenServiceIT` (Testcontainers): two concurrent `rotate()` calls with the same token — exactly one succeeds, the second triggers re-use detection | ⬜ |
| T-12 | Implement `AuthService`: register, login (returns access token + sets cookie), refresh (token rotation with `SELECT ... FOR UPDATE`), logout (set `revocation_reason='LOGOUT'` + clear cookie — no re-use detection on logout) | ⬜ |
| T-13 | Implement `AuthController` with full routes `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`; set cookie with `HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800`; read `security.cookie.secure` property (default `true`) to conditionally add `Secure` attribute — `true` in `prod`, `false` in `dev` profile | ⬜ |
| T-14 | Implement `RateLimiter` interface and `InProcessRateLimiterService` (sliding-window, per-IP, 5 attempts / 15 min); apply to `/api/v1/auth/login`; document single-instance limitation and Redis migration path in Javadoc | ⬜ |
| T-14b | Implement `OriginRefererFilter`: strict normalised origin comparison (parse URI → lowercase scheme+host → drop default port → strip path); reject malformed values, absent headers, and non-allowlist origins with `403`; skip on non-auth endpoints | ⬜ |
| T-15 | Write `TokenServiceTest`: rotation sets `revocation_reason='ROTATED'`; re-use of ROTATED token sets all active tokens to `revocation_reason='REUSE_DETECTED'` and returns `401`; re-use of REUSE_DETECTED token returns `401` without triggering another bulk revocation or re-use-detection cycle; re-use of LOGOUT token returns `InvalidTokenException` without calling `revokeAllForUser`; expired token returns `InvalidTokenException` | ⬜ |
| T-15b | Write `RateLimiterServiceTest`: 5 attempts pass; 6th returns `false`; counter resets after window expiry | ⬜ |
| T-16 | Write `AuthControllerIT` (MockMvc + Testcontainers): logout clears cookie + sets LOGOUT revocation reason; subsequent refresh returns `401` without revoking other sessions; 6th login attempt returns `429`; refresh cookie has `Secure=true` in prod profile and `Secure=false` in dev profile; refresh with spoofed origin returns `403`; refresh with correct origin succeeds | ⬜ |
| T-16b | Write `OriginRefererFilterTest`: missing both headers → `403`; disallowed origin → `403`; malformed/unparseable origin → `403`; suffix-match attack (evil.example.com vs trusted.example.com) rejected; allowed origin exact-match passes; Referer with path extracts origin correctly; `HTTPS://Trusted.Example.Com:443` normalises to `https://trusted.example.com` and passes; `HTTP://Trusted.Example.Com:443` normalises to `http://trusted.example.com:443` and is rejected (scheme never promoted; non-default port for HTTP preserved) | ⬜ |

---

## Phase 3 — Shared: Ownership + Country Allowlist + Module Boundaries

| ID | Task | Status |
|----|------|--------|
| T-17 | Implement `CountryCodes` class in `shared/country/` — static ISO 3166-1 alpha-2 allowlist; expose `isValid(String code)` | ⬜ |
| T-18 | Add `findByIdAndUserId` to every user-scoped repository (`AccountRepository`, `TransactionRepository`, `BudgetRepository`, `CashFlowSnapshotRepository`, `FraudAlertRepository`); add join-based `findByIdAndUserId` to `BudgetAlertRepository` (joins through `budgets.user_id`) | ⬜ |
| T-19 | Document and enforce ownership pattern in `shared/` Javadoc: `userId` always from `SecurityContextHolder`; cross-user access throws domain `NotFoundException` mapped to `404` | ⬜ |
| T-19b | Write `ModuleBoundaryTest` (ArchUnit): assert no module imports another module's `repository` package; run as part of standard test suite | ⬜ |
| T-19c | Write `SchemaIT` (Testcontainers): assert same-module FK constraints exist (`refresh_tokens → users`, `budget_alerts → budgets`); assert cross-module pairs have no FK constraint in information schema | ⬜ |

---

## Phase 4 — Accounts Module

| ID | Task | Status |
|----|------|--------|
| T-20 | ~~Create `accounts` Flyway migration~~ — covered by T-06 (`V3__accounts_table.sql`) | ⬜ |
| T-21 | Implement `Account` entity (fields: `id`, `userId`, `name`, `type`, `openingBalance`, `balance`, `currency`, `active`, `createdAt`), `AccountRepository` (with `findByIdAndUserId`), `AccountService`; `balance` initialised to `openingBalance` on creation; `applyDelta(accountId, delta)` updates `balance` only | ⬜ |
| T-22 | Implement `AccountController` (list, create, get, deactivate) | ⬜ |
| T-23 | Write `AccountServiceTest`: cross-user access returns `AccountNotFoundException` | ⬜ |
| T-24 | Write `AccountControllerIT`: user B cannot read user A's account (verify `404`) | ⬜ |

---

## Phase 5 — Transactions Module

| ID | Task | Status |
|----|------|--------|
| T-25 | ~~Create `transactions` Flyway migration~~ — covered by T-06 (`V4__transactions_table.sql`) | ⬜ |
| T-26 | Implement `Transaction` entity, `Category` enum, `TransactionRepository` (with ownership-scoped queries) | ⬜ |
| T-27 | Implement `CategoryResolver` (keyword-based auto-categorisation) | ⬜ |
| T-28 | Implement `TransactionService` (CRUD, filter, paginate); enforce creation ordering (validate countryCode → validate account active → run fraud rules → INSERT transaction → UPDATE balance → INSERT fraud alerts), all in one `@Transactional` block; apply delta-only balance update on edit; validate `countryCode` against `CountryCodes`; return `400` for unknown codes; return `409` for deactivated account | ⬜ |
| T-28b | Implement account balance consistency: `AccountService.applyDelta(accountId, delta)` runs `UPDATE accounts SET balance = balance + :delta WHERE id = :id`; never touches `opening_balance`; called only from `TransactionService`; never called directly from controllers or other services | ⬜ |
| T-29 | Implement `TransactionController` | ⬜ |
| T-30 | Write `TransactionServiceTest`: cross-user access returns `404`; invalid `countryCode` returns `400`; deactivated account returns `409`; create sets `balance = openingBalance + amount`; edit applies net delta only; delete reverses amount; `opening_balance` never changes in any test | ⬜ |
| T-30b | Write `TransactionServiceIT` (Testcontainers): forced failure on balance update rolls back transaction insert; forced failure on fraud-alert insert rolls back both transaction and balance | ⬜ |

---

## Phase 6 — Budgeting Module

| ID | Task | Status |
|----|------|--------|
| T-31 | ~~Create `budgets` and `budget_alerts` Flyway migration~~ — covered by T-06 (`V5__budgets_tables.sql`) | ⬜ |
| T-32 | Implement `Budget` entity, `BudgetRepository` (with `findByIdAndUserId`), `BudgetService` | ⬜ |
| T-33 | Implement `BudgetAlertService` (fires at 80 % and 100 %) | ⬜ |
| T-34 | Implement `BudgetController` (CRUD + progress endpoint with `?year=YYYY&month=MM` query params; default to current period) | ⬜ |
| T-35 | Write unit + integration tests for budgeting; include cross-user `404` test on all endpoints | ⬜ |
| T-35b | Write `BudgetServiceTest`: cross-user access to budget returns `BudgetNotFoundException`; alert for budget not owned by caller is inaccessible | ⬜ |
| T-35c | Write `BudgetControllerIT`: user B cannot read, edit, delete, or view progress for user A's budget (verify `404` on each endpoint); verify historical period query (`?year=2025&month=3`) returns correct progress | ⬜ |

---

## Phase 7 — Analytics Module

| ID | Task | Status |
|----|------|--------|
| T-36 | Implement `AnalyticsService` (category breakdown, monthly trend, top categories); all queries scoped by `userId` from security context | ⬜ |
| T-37 | Implement `AnalyticsController` | ⬜ |
| T-38 | Write unit tests for analytics calculations | ⬜ |

---

## Phase 8 — Cash-Flow Forecast Module

| ID | Task | Status |
|----|------|--------|
| T-39 | ~~Create `cash_flow_snapshots` Flyway migration~~ — covered by T-06 (`V6__cash_flow_snapshots_table.sql`; include `low_confidence` and `confidence_reason` columns) | ⬜ |
| T-40 | Implement `RecurringPatternDetector` (same amount ± 2 %, same day-of-month ± 2 days, ≥ 2 occurrences) | ⬜ |
| T-41 | Implement `CashFlowService`: build forecast; set `lowConfidence=true` when < 30 days history; set `reason="NO_HISTORY"` when zero transactions; preserve previous snapshot on recalculation failure | ⬜ |
| T-42 | Implement `CashFlowScheduler` (`@Scheduled` nightly 02:00; batch size 100; per-user failure isolation — log and continue) | ⬜ |
| T-43 | Implement `CashFlowController`: `GET /api/v1/cashflow/forecast` with `?horizon=30` (default), `?horizon=60`, or `?horizon=90` query parameter; `POST /api/v1/cashflow/refresh` triggers recalculation for all three horizons (1-per-hour per-user rate limit; `429` + `Retry-After` on excess) | ⬜ |
| T-44 | Write `RecurringPatternDetectorTest`: detects recurring items; handles empty input; handles < 30-day input | ⬜ |
| T-45 | Write `CashFlowServiceTest`: low-confidence flag set when < 30 days history; zero-history response shape (`lowConfidence: true`, `reason: "NO_HISTORY"`); failure preserves previous snapshot; all three horizons built on recalculation | ⬜ |
| T-46 | Write `CashFlowSchedulerTest`: single failure does not abort batch; all users processed | ⬜ |
| T-47 | Write `CashFlowControllerIT`: manual refresh rate limit returns `429` on second call within one hour | ⬜ |

---

## Phase 9 — Fraud Detection Module

| ID | Task | Status |
|----|------|--------|
| T-48 | ~~Create `fraud_alerts` Flyway migration~~ — covered by T-06 (`V7__fraud_alerts_table.sql`) | ⬜ |
| T-49 | Implement `FraudRule` sealed interface + `DuplicateTransactionRule` | ⬜ |
| T-50 | Implement `AbnormalAmountRule`: minimum history threshold is **fixed at 5** (hardcoded constant, not a config property — MVP only); always skip when fewer than 5 category transactions exist in last 30 days; skip when category mean = 0; document constant in Javadoc | ⬜ |
| T-51 | Implement `UnseenCountryRule`: skip on user's first-ever transaction | ⬜ |
| T-52 | Implement `FraudDetectionService`: runs all rules synchronously (read-only); returns list of fired rules; called from `TransactionService.create()` **before** the transaction INSERT; fraud alerts are INSERT-ed after the transaction INSERT within the same `@Transactional` block | ⬜ |
| T-53 | Implement `FraudAlertController` (list, acknowledge); ownership check on acknowledge | ⬜ |
| T-54 | Wire `FraudDetectionService` into `TransactionService.create()` | ⬜ |
| T-55 | Write `DuplicateTransactionRuleTest`: boundary at 60 s (before fires, after does not; no prior transactions skips) | ⬜ |
| T-56 | Write `AbnormalAmountRuleTest`: ≥5 txns + amount > 3×mean → fires; exactly 4 txns → skips (no alert); exactly 5 txns → evaluates normally; zero mean → skips; no config property read (threshold is a hardcoded constant) | ⬜ |
| T-57 | Write `UnseenCountryRuleTest`: first transaction skips; known country passes; new country fires | ⬜ |
| T-58 | Write `FraudDetectionServiceIT` (Testcontainers): end-to-end transaction creation triggers correct alerts; fraud-alert INSERT failure rolls back transaction and balance | ⬜ |

---

## Phase 10 — Shared Infrastructure

| ID | Task | Status |
|----|------|--------|
| T-59 | Implement `GlobalExceptionHandler` (RFC 7807 `ProblemDetail`); map all domain `NotFoundException` subclasses to `404` | ⬜ |
| T-60 | Implement `PageResponse<T>` wrapper for all paginated endpoints | ⬜ |
| T-61 | Implement `DataSeeder` annotated `@Profile("dev")` — demo user, 2 accounts, 180 transactions with country codes, 3 budgets, 1 fraud alert, 1 pre-seeded forecast snapshot; guard with empty-table check on startup | ⬜ |
| T-61b | Write `DataSeederProfileTest`: verify `DataSeeder` bean is present in `dev` context and absent in `prod` context; verify `prod` context start against Testcontainers DB leaves `users` table empty | ⬜ |
| T-62 | Configure CORS (per-profile allowed origins) | ⬜ |
| T-63 | Configure HTTPS for non-local profiles | ⬜ |

---

## Phase 11 — Frontend Foundation

| ID | Task | Status |
|----|------|--------|
| T-64 | Set up Axios instance with JWT interceptor: attach `Authorization: Bearer` header; handle 401 with single-flight refresh (module-level `refreshPromise` gate — first 401 fires refresh, subsequent concurrent 401s wait on same promise; on success all queued requests retry; on failure all reject and redirect to `/login`); access token in memory only, never `localStorage` | ⬜ |
| T-64b | Write `axiosInterceptor.test.ts` (Vitest): 3 concurrent 401 responses → exactly 1 refresh call → all 3 retried with new token; refresh failure → all 3 reject → redirect to `/login` fired | ⬜ |
| T-65 | Implement auth pages: Register, Login | ⬜ |
| T-66 | Implement protected route wrapper | ⬜ |
| T-67 | Implement Dashboard layout (sidebar navigation) | ⬜ |
| T-68 | Implement Accounts page | ⬜ |
| T-69 | Implement Transactions page (list + filter; `countryCode` field in add-transaction form) | ⬜ |
| T-70 | Implement Budgets page (progress bars) | ⬜ |
| T-71 | Implement Analytics page (category chart, trend chart) | ⬜ |
| T-72 | Implement Cash Flow forecast page: `horizon` selector (30 / 60 / 90 days); show `lowConfidence` warning banner; show `reason` text when `NO_HISTORY` | ⬜ |
| T-73 | Implement Fraud Alerts notification badge + list | ⬜ |

---

## Phase 12 — Testing & Quality Gate

| ID | Task | Status |
|----|------|--------|
| T-74 | Verify unit test coverage ≥ 80 % on all service classes | ⬜ |
| T-75 | Run full integration test suite against Testcontainers MySQL | ⬜ |
| T-76 | Run frontend component tests with Vitest | ⬜ |
| T-77 | Manual smoke test: register → account → transaction (with countryCode) → budget → forecast (all 3 horizons) → fraud alert → acknowledge | ⬜ |
| T-78 | Cross-user isolation smoke test: two demo users cannot access each other's data (verify `404` on each module) | ⬜ |
| T-79 | Auth security smoke test: revoked token rejected; re-use triggers full revocation; login rate limit fires | ⬜ |
| T-80 | WCAG 2.1 AA accessibility audit on primary flows | ⬜ |
