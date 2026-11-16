# FinSight AI — Product Overview

## Vision

Give individuals and small businesses one place to understand, plan, and act on their financial life — powered by AI insights and real-time data.

## Target Users

- **Individuals** tracking personal spending, savings goals, and investments.
- **Small business owners** monitoring cash flow and forecasting runway.

## Core Problems Solved

1. Fragmented financial data across multiple accounts and cards.
2. Reactive money management — no early warnings for budget overruns or fraud.
3. Poor visibility into future cash position.
4. Lack of personalised, plain-language financial advice.

## MVP Scope

The MVP delivers a **secure, working foundation** with real value:

| Feature                  | MVP | Post-MVP |
|--------------------------|-----|----------|
| Auth (register / login)  | ✅  |          |
| Account management       | ✅  |          |
| Transaction CRUD         | ✅  |          |
| Budgeting                | ✅  |          |
| Spending analytics       | ✅  |          |
| Cash-flow forecast       | ✅  |          |
| Investment simulation    |     | ✅        |
| Fraud detection          | ✅  |          |
| AI insights              |     | ✅        |
| PDF/CSV reporting        |     | ✅        |

## Key Constraints

- **Privacy first** — no third-party analytics SDKs on financial pages.
- **Synthetic data only** during development and CI.
- **No real payment processing** in MVP.
- Accessible: WCAG 2.1 AA minimum.

## Success Metrics (MVP)

- User can register, link a synthetic account, and view a budget dashboard in < 3 minutes.
- Fraud alert fires within one transaction of a rule violation.
- 30-day cash-flow forecast is visible from the dashboard.
