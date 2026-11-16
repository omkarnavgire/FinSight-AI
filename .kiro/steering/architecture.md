# FinSight AI — Architecture

## Style: Modular Monolith

One deployable JAR. Each module is a self-contained Java package with its own:
- domain model
- repository
- service
- REST controller
- module-level tests

Modules communicate **only through well-defined internal APIs (interfaces)**, never by reaching into another module's repository directly.

```
finsight-ai/
├── backend/
│   └── src/main/java/com/finsight/
│       ├── auth/
│       ├── accounts/
│       ├── transactions/
│       ├── budgeting/
│       ├── analytics/
│       ├── cashflow/
│       ├── investments/
│       ├── fraud/
│       ├── aiinsights/
│       ├── reporting/
│       └── shared/          # cross-cutting: DTOs, exceptions, utils
└── frontend/
    └── src/
        ├── features/        # one folder per module
        └── shared/
```

---

## Key Design Decisions

### Authentication & Token Management

**JWT flow**
- Access token: 15-minute expiry, signed HS256, returned in the JSON response body.
- Refresh token: 7-day expiry, returned and transmitted exclusively via an HttpOnly cookie.

**Refresh token storage and hashing**
- The raw refresh token is a cryptographically random 256-bit value (SecureRandom).
- Only `SHA-256(rawToken)` is stored in the `refresh_tokens` table — the plaintext never touches the database.
- On every `/auth/refresh` call, the incoming raw token is hashed and looked up. A match that is not revoked and not expired issues a new access token.

**Token rotation**
- Each successful refresh call issues a **new** refresh token and immediately revokes the old one (`revoked=true, revocation_reason='ROTATED'`).
- The row is locked with `SELECT ... FOR UPDATE` before writing, so two concurrent requests with the same token cannot both succeed.
- The new token is written and the old one is revoked inside a single `@Transactional` block.

**Revocation and logout**
- `/auth/logout` sets `revoked=true, revocation_reason='LOGOUT'` on the user's active refresh token and clears the cookie (`Max-Age=0`).
- If a `ROTATED`-revoked token is presented, every currently active refresh token for that user is revoked with `revocation_reason='REUSE_DETECTED'` (possible token theft), and the request returns `401`.
- If a `REUSE_DETECTED`-revoked token is subsequently presented, the request returns `401`. Re-use detection is **not** triggered again — this avoids cascading bulk-revocation cycles.
- If a `LOGOUT`-revoked token is presented, only `401` is returned — no re-use detection, no other sessions affected.

**Revocation reason reference**

| `revocation_reason` | Set by | Subsequent presentation |
|---------------------|--------|------------------------|
| `ROTATED` | Successful rotation | Triggers re-use detection; all active tokens → `REUSE_DETECTED`; returns `401` |
| `LOGOUT` | Explicit logout | Returns `401`; no other effect |
| `REUSE_DETECTED` | Re-use detection cycle | Returns `401`; no further re-use detection |

**Cookie configuration**

| Attribute | Value | Condition |
|-----------|-------|-----------|
| `HttpOnly` | true | always |
| `Secure` | true | prod and all non-local profiles |
| `Secure` | false | local dev profile only (HTTP without TLS) |
| `SameSite` | `Strict` | always |
| `Path` | `/api/v1/auth` | always |
| `Max-Age` | 604800 (7 days) | always |

`AuthController` reads `security.cookie.secure` (default `true`; set `false` in `application-dev.yml` only).

**CSRF**
- Because the refresh cookie is `SameSite=Strict`, cross-site CSRF attacks against `/auth/refresh` are blocked at the browser level.
- The access token travels in the `Authorization: Bearer` header (never in a cookie), so all other endpoints are CSRF-safe by default — no additional CSRF token is needed.
- If `SameSite=Strict` is ever relaxed (e.g., for federated login), a synchroniser-token CSRF filter must be added before that change ships.

**Spring Security filter chain**
- `JwtAuthFilter` runs before `UsernamePasswordAuthenticationFilter`.
- Role model: `USER`, `ADMIN`.
- Rate limiting on `/auth/login`: 5 attempts per 15 minutes per IP (in-process `RateLimiter`; replace with Redis for multi-instance).

**Acceptance criteria**
- AC-AUTH-1: A refresh token presented after logout (`LOGOUT`-revoked) returns `401` and does not revoke other sessions.
- AC-AUTH-2: Re-use of a `ROTATED`-revoked token sets every currently active refresh token for that user to `revoked=true, revocation_reason='REUSE_DETECTED'` and returns `401`.
- AC-AUTH-2b: A token already revoked with `REUSE_DETECTED` returns `401` on subsequent presentation without triggering another re-use detection cycle (no further bulk revocation).
- AC-AUTH-3: The `refresh_tokens` table never contains a plaintext token value.
- AC-AUTH-4: The refresh cookie is absent from responses to non-auth endpoints.
- AC-AUTH-5: Login is blocked after 5 failed attempts within 15 minutes from the same IP.
- AC-AUTH-6: Two concurrent refresh requests with the same token result in exactly one success; the other triggers re-use detection.
- AC-AUTH-7: `Secure=false` cookie is set in the `dev` profile; `Secure=true` in `prod`.

**Tests**
- `TokenServiceTest`: rotation sets `revocation_reason='ROTATED'`; ROTATED re-use sets all active tokens to `revocation_reason='REUSE_DETECTED'` and returns `401`.
- `TokenServiceTest`: REUSE_DETECTED token returns `401` on subsequent use without calling `revokeAllForUser` again.
- `TokenServiceTest`: LOGOUT re-use returns `401` without calling `revokeAllForUser`.
- `AuthControllerIT`: logout clears cookie and invalidates token; other sessions unaffected.
- `TokenServiceIT`: concurrent rotation test — exactly one succeeds.
- `AuthControllerIT`: 6th login attempt within 15 min returns `429`.

---

### User Ownership (Data Isolation)

Every service method that reads or writes user-scoped data must enforce ownership. This is a **mandatory cross-cutting rule**, not optional.

**Rules**
1. All repository queries for user-scoped entities (accounts, transactions, budgets, forecasts, fraud alerts) accept `userId` as an explicit parameter and include `WHERE user_id = :userId` (or join equivalent).
2. No service method accepts a bare entity `id` without also accepting and verifying the `userId` from the security context.
3. `@PreAuthorize("isAuthenticated()")` on every controller method; `userId` is always extracted from `Authentication`, never from a request parameter or body.
4. If a resource is not found **or** belongs to a different user, the response is `404 Not Found` (not `403`) to avoid leaking existence.

**Pattern**
```
// Service layer — mandatory shape
Account findAccount(Long accountId, Long userId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
}
```

**Acceptance criteria**
- AC-OWN-1: A request for account `id=5` by user B (who does not own it) returns `404`, not the account data.
- AC-OWN-2: No service method queries a user-scoped table without a `userId` predicate.
- AC-OWN-3: `userId` in queries always comes from the Spring Security context, never from the request payload.

**Tests**
- `AccountServiceTest`: cross-user access returns `AccountNotFoundException`.
- `TransactionControllerIT`: authenticated user B cannot read, edit, or delete user A's transactions.
- Repeat pattern for Budgets, CashFlow, FraudAlerts.

---

### Data

- MySQL 8 with Flyway migrations.
- Each module owns its tables (no cross-module foreign keys at DB level — ownership enforced in code).
- Synthetic data seeded via `DataSeeder` Spring component, active on the `dev` profile only.

---

### API

- RESTful JSON over HTTPS.
- Versioned: `/api/v1/...`
- Global `@RestControllerAdvice` returns RFC 7807 `ProblemDetail`.
- Pagination on all list endpoints via `PageResponse<T>`.

---

### Security Layers

```
Request
  → HTTPS (TLS)
  → CORS filter (allowed origins whitelist, per profile)
  → Rate-limit filter (login endpoint)
  → JWT auth filter  (validates access token, sets SecurityContext)
  → Method-level @PreAuthorize
  → Input validation (Jakarta Bean Validation)
  → Ownership check in service layer (userId from SecurityContext)
  → Parameterised queries (JPA / Spring Data)
  → Response (no sensitive fields; ownership re-checked before serialisation)
```

---

### Frontend

- React 19 + TypeScript.
- Vite for build tooling.
- React Query for server state.
- React Router v7 for navigation.
- Axios instance with JWT interceptor: attaches `Authorization: Bearer` header, handles 401 by calling `/api/v1/auth/refresh` once then retrying. Access token kept in memory only (never `localStorage`).

#### Single-flight 401 refresh

When multiple concurrent requests return `401` simultaneously (e.g. after an access token expires), the interceptor must not fire multiple parallel refresh calls. A module-level `refreshPromise` variable gates the call:

1. First `401` seen → set `refreshPromise` to the refresh call, await it.
2. Any subsequent `401` while `refreshPromise` is set → join the existing promise, do not call refresh again.
3. On refresh success → clear `refreshPromise`; retry all queued requests with the new token.
4. On refresh failure → clear `refreshPromise`; reject all queued requests; redirect to `/login`.

**Acceptance criteria**
- AC-SF-1: 3 concurrent `401` responses produce exactly 1 `/api/v1/auth/refresh` call.
- AC-SF-2: All 3 requests are retried with the new token after the single refresh resolves.
- AC-SF-3: If refresh fails, all 3 requests reject and the user is redirected to `/login`.

**Tests**
- `axiosInterceptor.test.ts` (Vitest): 3 concurrent 401s → 1 refresh call → all 3 retried; refresh failure → all 3 reject → redirect fired.

---

### Testing Strategy

| Level | Tool | Coverage target |
|-------|------|-----------------|
| Unit | JUnit 5 + Mockito | 80 % on service classes |
| Integration | Spring Boot Test + Testcontainers (MySQL) | all key flows |
| API | MockMvc / RestAssured | all endpoints |
| Frontend unit | Vitest + React Testing Library | components |
| E2E (post-MVP) | Playwright | critical paths |

---

### Fraud Detection (MVP)

Rule-based engine in the `fraud` module. Each rule implements the `FraudRule` sealed interface. `FraudDetectionService` runs all rules on every new transaction synchronously before the transaction is persisted.

#### Rule 1 — Duplicate Transaction
- Flag if an identical (same account, same amount, same description) transaction exists within 60 seconds.
- **Edge case**: no prior transactions → rule is skipped (cannot duplicate what does not exist).

#### Rule 2 — Abnormal Amount
- Flag if `amount > 3 × mean(category amounts over last 30 days)`.
- **Minimum history threshold (MVP fixed constant)**: the threshold is **5 transactions** in the same category over the last 30 days. This value is a hardcoded constant in `AbnormalAmountRule` — there is no configurable property for the MVP.
- **Insufficient history**: if fewer than 5 transactions exist in the category over the last 30 days, the rule is **always skipped** and no alert is raised. The transaction is accepted normally.
- **Edge case — zero mean**: if the category mean is zero (e.g., all prior transactions are refunds), the rule is skipped to avoid division by zero.

#### Rule 3 — Unseen Country
- Flag if `transaction.countryCode` has never appeared in any of the user's prior transactions.
- **Country metadata source**: `countryCode` is a two-character ISO 3166-1 alpha-2 field on the `Transaction` entity, supplied by the client at transaction creation time. It is validated against a static allowlist of valid ISO codes held in `shared/country/CountryCodes.java` (generated from the ISO 3166-1 dataset). Unknown or blank codes are rejected with `400 Bad Request` before fraud evaluation.
- **Edge case — first transaction ever**: the user has no prior country history, so the rule is skipped on the very first transaction.

**Alerts**: stored in `fraud_alerts`; surfaced as a dashboard notification. Users can acknowledge (dismiss) an alert.

**Acceptance criteria**
- AC-FRAUD-1: Duplicate rule fires when two identical transactions arrive within 60 s; does not fire after 60 s.
- AC-FRAUD-2: Abnormal-amount rule is **skipped** (no alert raised) when fewer than 5 transactions exist in the same category over the last 30 days. The fixed threshold of 5 is a hardcoded constant; there is no configurable property.
- AC-FRAUD-3: Abnormal-amount rule is skipped when the category mean is zero.
- AC-FRAUD-4: Unseen-country rule is skipped on a user's first-ever transaction.
- AC-FRAUD-5: An invalid or blank `countryCode` is rejected `400` before fraud rules run.
- AC-FRAUD-6: All three rules are tested independently with mocked repositories; no DB required for unit tests.

**Tests**
- `DuplicateTransactionRuleTest`: boundary at exactly 60 s, before 60 s, after 60 s.
- `AbnormalAmountRuleTest`: exactly 4 category transactions in last 30 days → rule skipped; exactly 5 → rule evaluates normally (fires when amount > 3×mean); zero mean → skipped; ≥5 txns with amount ≤ 3×mean → no alert.
- `UnseenCountryRuleTest`: first transaction skips; known country passes; new country fires.
- `FraudDetectionServiceIT`: end-to-end via Testcontainers — transaction creation triggers correct alerts.

---

### Cash-Flow Forecast

#### Model
- Horizon: 30 days (MVP); 60 and 90 days available via query parameter.
- Input: transactions from the last 90 days. Recurring items are detected by `RecurringPatternDetector` (same amount ± 2 %, same day-of-month ± 2 days, occurring at least twice).
- Output: a `CashFlowSnapshot` with a JSON array of `{date, projected_balance}` stored per user.

#### Insufficient history
- If fewer than 30 days of transaction history exist, the forecast is generated from available data with a `lowConfidence: true` flag in the response.
- If zero transactions exist, the endpoint returns a forecast of flat balance (no income, no expenses) with `lowConfidence: true` and a `reason: "NO_HISTORY"` field.

#### Manual refresh
- `POST /api/v1/cashflow/refresh` triggers an immediate recalculation for the authenticated user, replacing their current snapshot.
- Rate-limited to once per hour per user to prevent abuse.

#### Scheduled recalculation
- `CashFlowScheduler` runs nightly at 02:00 server time via Spring `@Scheduled(cron = "0 0 2 * * *")`.
- Processes users in batches of 100 to avoid memory spikes.
- If a user's recalculation fails, the error is logged and the scheduler continues to the next user (no partial batch rollback).
- The previous snapshot is kept if recalculation fails, so the user always sees a (possibly stale) forecast rather than nothing.

**Acceptance criteria**
- AC-CF-1: Forecast with < 30 days of history returns `lowConfidence: true`.
- AC-CF-2: Forecast with zero history returns `lowConfidence: true` and `reason: "NO_HISTORY"`.
- AC-CF-3: Manual refresh replaces the snapshot and respects the 1-per-hour rate limit.
- AC-CF-4: A single-user recalculation failure during the nightly run does not abort the batch.
- AC-CF-5: The previous snapshot is preserved when recalculation fails.

**Tests**
- `RecurringPatternDetectorTest`: detects recurring items; handles empty input; handles < 30 days input.
- `CashFlowServiceTest`: low-confidence flag set correctly; zero-history response shape.
- `CashFlowSchedulerTest`: single failure does not propagate; batch processes all users.
- `CashFlowControllerIT`: manual refresh rate limit returns `429` on second call within one hour.

---

### AI Insights (Post-MVP)

Provider: OpenAI API or local Ollama, configured per profile. Called from the `aiinsights` module.

#### Data minimisation
- Prompts contain **only aggregated statistics** (category totals, monthly averages, net worth bracket).
- Raw transaction descriptions, account names, and balances are never included in prompts.
- User PII (name, email) is never included in prompts.

#### Prompt-injection defenses
- All user-supplied values included in prompts are sanitised: stripped of special characters (`<`, `>`, `{`, `}`, `\n`, `\r`, backticks) before interpolation.
- System prompt and user-data sections are separated by a fixed delimiter that the model is instructed not to override.
- The system prompt explicitly instructs the model to refuse any attempt to change its role or reveal its instructions.
- Prompt structure is validated (expected field count and types) before the API call; a malformed structure throws `PromptBuildException` and the call is aborted.

#### Output validation
- The LLM response is validated against a whitelist schema (string, max 2000 chars, no HTML tags, no URLs).
- Responses failing validation are discarded; the user sees a cached result or a generic fallback message, never raw LLM output that failed checks.
- Financial figures extracted from LLM output are cross-checked against the aggregated data that was sent in. Any figure that differs by more than 20 % is flagged and the response is replaced with the fallback.

#### Timeouts and rate limits
- HTTP call to the AI provider times out after 10 seconds (`connectTimeout = 3 s`, `readTimeout = 10 s`).
- Per-user rate limit: 10 AI insight requests per hour.
- Global rate limit: 100 AI insight requests per minute across all users (protects API spend).
- Both limits return `429 Too Many Requests` with a `Retry-After` header.

#### Caching
- Results cached per user for 24 hours in the `ai_insight_cache` table (or Redis post-MVP).
- Cache is invalidated when the user's transaction data changes materially (net spend change > 5 % vs. cache time).

**Acceptance criteria**
- AC-AI-1: A prompt never contains raw transaction descriptions or user PII.
- AC-AI-2: A user-supplied string with `\n`, backticks, or `{` is sanitised before prompt inclusion.
- AC-AI-3: An LLM response exceeding 2000 chars or containing HTML tags is discarded and replaced with the fallback.
- AC-AI-4: The AI endpoint returns `429` after 10 requests within one hour from the same user.
- AC-AI-5: The AI call returns a timeout error (not a 500) if the provider does not respond within 10 s.

**Tests**
- `PromptBuilderTest`: PII fields absent from built prompt; injection characters stripped.
- `AiInsightServiceTest`: output exceeding length limit triggers fallback; HTML in output triggers fallback.
- `AiInsightServiceTest`: timeout from mock provider returns graceful error, not exception propagation.
- `AiInsightControllerIT`: 11th request within one hour returns `429`.
