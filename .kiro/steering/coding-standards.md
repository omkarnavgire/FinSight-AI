# FinSight AI — Coding Standards

## Java / Spring Boot

### General
- Java 25. Use records for immutable DTOs, sealed classes for domain variants.
- Prefer `var` for local variables where the type is obvious from the right-hand side.
- No `null` in public APIs — use `Optional` or throw a named exception.
- All public methods on services and controllers must have Javadoc.

### Naming
| Element | Convention | Example |
|---------|------------|---------|
| Class | PascalCase | `TransactionService` |
| Method / variable | camelCase | `findByAccountId` |
| Constant | UPPER_SNAKE | `MAX_RETRY_COUNT` |
| Package | lowercase, singular | `com.finsight.transaction` |
| DB table | snake_case, plural | `financial_transactions` |
| DB column | snake_case | `created_at` |

### Layers
- **Controller** — input validation only, delegates to service, never touches repository.
- **Service** — business logic, transaction boundaries (`@Transactional`).
- **Repository** — Spring Data JPA interfaces; raw JPQL/native only when needed.
- **Domain** — JPA entities; no Jackson annotations on entities (use separate DTOs).

### Security
- Secrets from environment variables via `@Value` or `application-{profile}.yml` (never hardcoded).
- Always use parameterised queries — no string concatenation in JPQL or SQL.
- Validate all inputs with Jakarta Bean Validation (`@NotBlank`, `@Size`, `@Email`, etc.).
- Sensitive fields (`password`, `token`) must never appear in logs or API responses.
- Use `@PreAuthorize` for method-level access control.

### Error Handling
- Throw domain-specific exceptions (e.g., `AccountNotFoundException`).
- Global `@RestControllerAdvice` maps exceptions to RFC 7807 `ProblemDetail`.
- Never swallow exceptions with an empty catch block.

### Testing
- Test class named `<Subject>Test` for unit, `<Subject>IT` for integration.
- One assertion concept per test method.
- Use `@DisplayName` with plain English descriptions.
- Mock external dependencies with Mockito; use Testcontainers for real DB tests.

---

## TypeScript / React

### General
- TypeScript strict mode (`"strict": true` in tsconfig).
- No `any` — use `unknown` and narrow, or define a proper type.
- Functional components only. No class components.
- All props typed with an interface, named `<ComponentName>Props`.

### Naming
| Element | Convention | Example |
|---------|------------|---------|
| Component | PascalCase | `TransactionList` |
| Hook | camelCase, `use` prefix | `useTransactions` |
| File | kebab-case | `transaction-list.tsx` |
| Type / Interface | PascalCase | `TransactionDto` |
| Constant | UPPER_SNAKE | `API_BASE_URL` |

### State & Data Fetching
- Server state: React Query (`useQuery`, `useMutation`).
- UI state: `useState` / `useReducer` — keep it in the smallest scope.
- No prop drilling beyond 2 levels — use context or a query hook.

### API Layer
- All API calls go through a central Axios instance in `src/shared/api/client.ts`.
- Request/response types defined in `src/shared/types/api.ts`.
- Never call `fetch` directly in a component.

### Security (Frontend)
- Never store JWT access tokens in `localStorage` — keep in memory only.
- Refresh token lives in an HttpOnly cookie (set by backend).
- Sanitise any user-supplied content before rendering as HTML.

### Styling
- Tailwind CSS utility classes.
- No inline `style` props except for truly dynamic values.
- Responsive by default — mobile-first breakpoints.

---

## Git & Branching

- Branch: `feature/<ticket-id>-short-description`
- Commit message: `<type>(<scope>): <subject>` (Conventional Commits)
  - Types: `feat`, `fix`, `docs`, `chore`, `test`, `refactor`
  - Example: `feat(auth): add JWT refresh token endpoint`
- PRs require passing CI and at least one review before merge.
- Never commit secrets, credentials, or real financial data.

---

## File & Folder Rules

- One class per file (Java); one component per file (React).
- Keep files under 300 lines — split if longer.
- Delete dead code; do not comment it out and leave it.
