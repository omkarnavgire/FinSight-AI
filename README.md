# FinSight AI

An advanced financial intelligence platform built as a **Modular Monolith**.

## Tech Stack

| Layer        | Technology                        |
|--------------|-----------------------------------|
| Backend      | Java 25 · Spring Boot 4           |
| Frontend     | React · TypeScript                |
| Database     | MySQL 8                           |
| Security     | Spring Security · JWT             |
| Architecture | Modular Monolith                  |

## Modules

| Module              | Responsibility                                      |
|---------------------|-----------------------------------------------------|
| `auth`              | Registration, login, JWT issuance & refresh         |
| `accounts`          | Bank accounts, balances, synthetic data seeding     |
| `transactions`      | CRUD, categorisation, import                        |
| `budgeting`         | Budget rules, period tracking, alerts               |
| `analytics`         | Spending trends, category breakdowns, ratios        |
| `cashflow`          | 30/60/90-day forecasting                            |
| `investments`       | Portfolio simulation, return modelling              |
| `fraud`             | Rule-based anomaly detection                        |
| `ai-insights`       | LLM-backed financial advice summaries               |
| `reporting`         | PDF/CSV export, scheduled digests                   |

## Getting Started

Local development uses the `dev` profile (plain HTTP, local MySQL without TLS).
Put local-only values in `backend/application-local.yml` or `.env` (both git-ignored).

```bash
# Backend (requires Java 25; Maven wrapper included)
cd backend
export SPRING_PROFILES_ACTIVE=dev DB_USERNAME=... DB_PASSWORD=... JWT_SECRET=...
# Optional: seed synthetic demo data on an empty database (demo@finsight.dev)
export FINSIGHT_DEMO_PASSWORD=...   # choose your own; never commit it
./mvnw spring-boot:run

# Frontend (requires Node 20+)
cd frontend && npm install && npm run dev
```

## Production deployment

The `prod` profile (and any profile other than `dev`) enforces HTTPS. The app refuses to start
if a required value below is missing or unsafe; errors name the variable, never its value.

| Variable | Requirement |
|----------|-------------|
| `PUBLIC_HOST` | Public host (`host` or `host:port`) used as the HTTPS redirect target; the request's `Host` header is never used |
| `CORS_ALLOWED_ORIGINS` | Exact `https://host[:port]` origins, comma-separated; no wildcard |
| `TRUSTED_PROXY_REGEX` | Exact reverse-proxy IPs only, `\|`-separated with escaped dots, e.g. `10\.0\.0\.10\|10\.0\.0\.11`; no ranges or wildcards. Forwarded headers from any other address are ignored |
| `TRUSTED_PROXY_COUNT` | `0` (the proxy's `X-Forwarded-For` is read by Tomcat, not the app) |
| `JWT_SECRET` | At least 32 bytes |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | MySQL connection |
| `DB_TRUSTSTORE_PATH`, `DB_TRUSTSTORE_PASSWORD`, `DB_TRUSTSTORE_TYPE` | Truststore with the MySQL CA (default type `PKCS12`) |

Assumptions:

1. A reverse proxy terminates TLS, redirects port 80 to 443, overwrites `X-Forwarded-Proto` and `X-Forwarded-For`, and drops copies sent by clients.
2. The app is reachable only from that proxy.
3. MySQL serves TLS with a certificate whose name matches `DB_HOST`; the app connects with `sslMode=VERIFY_IDENTITY`.
4. Secrets come from the environment or a secrets manager, never from files in Git.
5. One instance only: the rate limiter is in memory.
6. The `dev` profile is never used outside a developer machine. The app refuses to start under `dev` if the database host is not `localhost`, `127.0.0.1` or `::1`. Any profile other than `dev` (including `test`) gets every production check.

## Security Notes

- All secrets in environment variables — never in source.
- JWT access tokens expire in 15 minutes; refresh tokens in 7 days.
- Passwords hashed with BCrypt (strength 12).
- HTTPS enforced in all non-local profiles (HTTP gets `308`; HSTS on HTTPS responses).

## Data

Synthetic financial data is used throughout development and testing.  
No real personal financial information is stored or required.

## Status

🟡 **MVP backend in progress** — phases follow `.kiro/specs/finsight-foundation/tasks.md`.

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Project scaffold, Flyway migrations V1–V7 | ✅ Done |
| 2 | Auth (JWT, refresh-token rotation, rate limiting) | ✅ Done |
| 3 | Shared ownership, country allowlist, module boundaries | ✅ Done |
| 4 | Accounts | ✅ Done |
| 5 | Transactions | ✅ Done |
| 6 | Budgeting and alerts | ✅ Done |
| 7 | Analytics | ✅ Done |
| 8 | Cash-flow forecast | ✅ Done |
| 9 | Fraud detection | ✅ Done |
| 10 | Shared infrastructure (errors, paging, dev seed data, CORS, HTTPS, DB TLS) | 🔄 Implemented, verification pending |
| 11–12 | Frontend, quality gate | ⬜ Not started |

Investments, AI insights and reporting are post-MVP.
