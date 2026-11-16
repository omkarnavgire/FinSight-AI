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

> Application code is not yet implemented. This section will be updated.

```bash
# Backend (requires Java 25 + Maven)
./mvnw spring-boot:run

# Frontend (requires Node 20+)
cd frontend && npm install && npm run dev
```

## Security Notes

- All secrets in environment variables — never in source.
- JWT access tokens expire in 15 minutes; refresh tokens in 7 days.
- Passwords hashed with BCrypt (strength 12).
- HTTPS enforced in all non-local profiles.

## Data

Synthetic financial data is used throughout development and testing.  
No real personal financial information is stored or required.

## Status

🟡 **Foundation phase** — documentation and architecture defined, implementation pending.
