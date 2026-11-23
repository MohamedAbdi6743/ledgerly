# Ledgerly

[![CI](https://github.com/MohamedAbdi6743/ledgerly/actions/workflows/ci.yml/badge.svg)](https://github.com/MohamedAbdi6743/ledgerly/actions/workflows/ci.yml)

A small core-banking system for a Canadian community credit union. Tellers open accounts and
handle cash, members move money between their accounts, and managers approve large transfers,
reverse mistakes and check that the books balance.

Built with Java, Spring Boot and PostgreSQL. The web pages (plain TypeScript) come later.

**Status:** work in progress. The database schema, staff-created accounts and login are built
(milestones 1–2 of 10). Moving money starts in milestone 3. Each section below says what's
built and what's planned.

## Why I built it

Small credit unions and savings groups often track money in spreadsheets. A balance gets typed
over, nobody can say why an account holds $812.40, and there's no record of who approved what.
Real banking systems solve this with a few strict rules, and those rules are what bank and
fintech interviews ask about.

Ledgerly is built around them:

- Every movement of money is recorded as ledger entries, so any balance can be explained.
- Each transaction is double-entry: its entries always add up to zero.
- Nothing is edited or deleted. Mistakes are fixed with a reversal.
- A request that gets retried can never move money twice.
- Transfers of $5,000 or more need a second person to approve them.

## Features

**Built**

- Tellers and managers create members; managers create tellers and other managers. There's no
  public sign-up, because a credit union checks identity first.
- Member numbers (`M000001`, …) from a database sequence
- One-time temporary passwords for new accounts, which must be changed before anything else works
- Session login and logout, roles (member, teller, manager), member search
- An audit log of staff actions and password changes
- The full ledger schema, with the money rules enforced by PostgreSQL itself

**Planned**

| Milestone | What |
|---|---|
| 3 | Open chequing and savings accounts; cash deposits and withdrawals that write balanced ledger entries under a row lock |
| 4 | Transfers with idempotency keys, a daily limit, and deadlock-free locking |
| 5 | Maker-checker approvals for $5,000+, reversals |
| 6 | Monthly statements, a reconciliation report, a report of cash deposits of $10,000+ |
| 7–8 | Web pages for members and staff |
| 9 | A live demo |

**Rules the database already enforces** (milestone 1)

- A member account can't go below $0 (`CHECK (type = 'SYSTEM' OR balance >= 0)`)
- Nobody can approve their own request (`CHECK (approved_by <> created_by)`)
- A transaction can be reversed only once, and a reversal must say what it reverses
- The same person can't use the same idempotency key twice
- Canadian dollars only; transaction amounts must be positive; ledger entries can't be $0
- Members have a member number and staff don't; system accounts have no owner

## Screenshots

None yet. The web pages arrive in milestones 7–8.

## Tech stack

| Part | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4, Spring Web MVC, Spring Security, Spring Data JPA / Hibernate |
| Database | PostgreSQL 18, schema managed by Flyway migrations |
| Frontend | Planned: plain TypeScript, HTML and CSS, no framework (same approach as my [HandsOn](https://github.com/MohamedAbdi6743/HandsOn) project) |
| Tests | JUnit 5, Mockito, MockMvc, Testcontainers (real PostgreSQL in Docker) |
| Tooling | Maven Wrapper, Docker Compose for the local database, GitHub Actions CI |

## Architecture

One Spring Boot application will serve both the REST API and the pages, so the browser talks to
a single origin (no CORS setup, and session cookies just work).

```
 Browser (HTML + compiled TypeScript, planned)
        │  fetch() with JSON, session cookie, CSRF header, Idempotency-Key header
        ▼
 Spring Security filter ── who is this? which role?
        ▼
 Temporary-password check ── 403 until a new password is set
        ▼
 Controllers   HTTP in, JSON out, @Valid request DTOs
        ▼
 Services      money rules, transactions, row locks, audit log
        ▼
 Repositories  Spring Data JPA
        ▼
 PostgreSQL    tables, foreign keys, unique and check constraints
```

- **Controllers** only translate HTTP. They never contain rules.
- **Services** hold every rule and return DTOs. The database session closes when a service
  method ends (`open-in-view` is off), so nothing lazy leaks into the JSON layer.
- **The database** is the last line of defense: constraints reject bad money data even if a
  bug slips through the Java code. There's a test suite just for those constraints.

The plain-English walkthrough is in [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md). Every table,
endpoint and design decision, with the alternatives, is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Database

```
 app_user 1 ──< account                 (a member owns accounts; system accounts have no owner)
 app_user 1 ──< journal_transaction     (created_by, approved_by)
 journal_transaction 1 ──< ledger_entry >── 1 account
 journal_transaction 0..1 ── reverses ── journal_transaction
 app_user 1 ──< audit_event
```

| Table | What it holds | Notable rules |
|---|---|---|
| `app_user` | Members and staff | Unique lowercase email, BCrypt hash, role `MEMBER`, `TELLER` or `MANAGER`, member number only for members |
| `account` | Chequing, savings, and the "Cash on hand" system account | CAD only; member accounts can't go negative; `balance` is a cached sum of entries |
| `journal_transaction` | One business event: deposit, withdrawal, transfer or reversal | Amount > 0, no self-approval, one reversal per transaction, `UNIQUE (created_by, idempotency_key)` |
| `ledger_entry` | The ledger itself, append-only | Signed amounts that sum to zero per transaction; never $0; `balance_after` for statements |
| `audit_event` | Who did what, and when | Append-only |

Times are stored as `TIMESTAMPTZ` (exact moments) and the app talks to the database in UTC.

## The core idea: a ledger, not a balance

The usual "bank app" stores a balance and changes it in place. When a number looks wrong,
there's no history to explain it.

Ledgerly records every movement as entries instead. A $50 transfer from chequing to savings
writes `-50.00` and `+50.00`. A $200 cash deposit writes `+200.00` to the member and
`-200.00` to "Cash on hand", the account that stands for the cash drawer. Every transaction sums
to zero, so the whole ledger always sums to zero, and the reconciliation report (milestone 6)
checks that, along with every cached balance against its entries.

The money-moving code arrives in milestones 3–4. The rules it depends on are already in the
database and tested.

**A bug the schema tests caught.** The first version of the rule "a transfer's two accounts must
differ" was `CHECK (from_account_id IS DISTINCT FROM to_account_id)`. A reversal has neither
account, and in SQL `NULL IS DISTINCT FROM NULL` is false, so that rule would have refused every
reversal. It now applies only when both accounts are set. Each schema test also checks the
name of the constraint that refused the data, so a test can't pass because a different rule
happened to fail.

## Running it locally

You need **Java 21** and **Docker**. Maven isn't required; the project includes the Maven Wrapper.

1. Create your local settings file, then open it and set your own passwords:

   ```bash
   cp .env.example .env
   ```

2. Start PostgreSQL:

   ```bash
   docker compose up -d
   ```

3. Start the app. Flyway creates the tables on the first start:

   ```bash
   ./mvnw spring-boot:run
   ```

   On Windows PowerShell use `.\mvnw.cmd spring-boot:run`.

4. There are no pages yet, so try the API, for example `GET http://localhost:8080/api/auth/csrf`
   (or the `APP_PORT` from your `.env`). Log in with the manager account from `.env`.

### Environment variables

Both Docker Compose and Spring Boot read these from `.env`, which Git ignores.

| Variable | Purpose | Example |
|---|---|---|
| `DB_PORT` | Port PostgreSQL listens on locally | `5441` |
| `DB_URL` | JDBC URL for the app | `jdbc:postgresql://localhost:5441/ledgerly` |
| `DB_USERNAME` / `DB_PASSWORD` | Database login | |
| `APP_PORT` | Port the app runs on | `8080` |
| `LEDGERLY_ADMIN_EMAIL` / `LEDGERLY_ADMIN_PASSWORD` / `LEDGERLY_ADMIN_NAME` | First manager, created at startup if it doesn't exist (password: at least 12 characters) | |

## Tests

Docker must be running; the integration tests start their own temporary PostgreSQL.

```bash
./mvnw test
```

48 tests, and GitHub Actions runs them on every push and pull request.

| Kind | Examples |
|---|---|
| Database constraints | Negative member balance, self-approval, double reversal, reused idempotency key, non-CAD currency, PostgreSQL rounding a third decimal |
| Unit tests (Mockito, fixed clock) | Member numbers, temporary passwords stored only as hashes, wrong current password |
| API integration tests (MockMvc + PostgreSQL) | Login and logout, the temporary-password gate, password rules, who can create whom, member search, audit entries |
| Security | CSRF required, roles enforced, Content-Security-Policy header, CSRF cookie readable by JavaScript (real server) |

Planned: a test that runs hundreds of random transactions and then proves the ledger still sums
to zero, and tests where many threads hit the same account at once.

## API

All endpoints are under `/api` and use JSON. Errors always look like
`{ "status": 409, "message": "...", "fieldErrors": { ... } }`.

**Built**

| Method | Path | Who | What |
|---|---|---|---|
| POST | `/auth/login` | anyone | Log in (form fields `email`, `password`) |
| POST | `/auth/logout` | logged in | Log out |
| GET | `/auth/me` | logged in | Current user |
| GET | `/auth/csrf` | anyone | Sends the CSRF cookie |
| POST | `/auth/password` | logged in | Change your password (12–72 characters) |
| POST | `/staff` | manager | Create a teller or manager (temporary password) |
| POST | `/members` | teller, manager | Create a member (temporary password) |
| GET | `/members?query=` | teller, manager | Search members by name, email or member number |
| GET | `/members/{id}` | teller, manager | One member |

**Planned:** accounts, deposits, withdrawals, transfers (all with an `Idempotency-Key` header),
approvals, reversals, statements and reports. The full list is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#5-api-design).

Status codes: `400` invalid input, `401` not logged in, `403` wrong role, missing CSRF token or
still on a temporary password, `404` not found, `409` valid input that breaks a rule.

## Decisions and trade-offs

- **Ledger entries plus a cached balance.** Entries give history and something to prove; the
  cached balance gives a fast read and a row to lock. Reconciliation checks they agree.
- **Signed amounts instead of debit and credit columns.** Easier to add up in code. The cost:
  accountants may expect debit/credit columns, so the mapping is documented.
- **Staff create accounts.** It matches how a credit union verifies identity, at the cost of a
  first-manager setup step from environment settings.
- **Temporary passwords checked on every request.** The check reads the flag from the database
  each time, so it lifts the moment the password changes. The cost is one small query per
  request.
- **Sessions instead of JWT.** The pages and API will share one origin, so an `HttpOnly` session
  cookie is simpler and can't be read by JavaScript. The cost: sessions live in memory, so a
  restart logs everyone out until they're moved to the database (planned with the live demo).
- **Audit lines in the same transaction.** `Propagation.MANDATORY` means an audit entry can't
  exist without its change, or the other way round.
- **`TIMESTAMPTZ` in UTC.** Money events need exact, unambiguous times, and a deployed server may
  not run in Toronto time.
- **Planned: pessimistic locks in id order.** Simple and deadlock-free. Optimistic locking would
  need retry logic for the same result.

## What I'd add after version 1

- Interest on savings accounts
- Holds and an "available balance" for pending transfers
- Real payment rails (Interac e-Transfer, EFT) instead of internal transfers only
- Password reset by email
- A simple fraud rule, such as many transfers in a short time

## Project layout

```
src/main/java/com/ledgerly/
  config/       security rules, temporary-password check, clock, first-manager setup
  controller/   REST endpoints
  service/      business rules, audit log
  repository/   Spring Data repositories
  entity/       JPA entities
  dto/          request and response records
  exception/    error format and global handler
src/main/resources/
  db/migration/ Flyway SQL migrations
docs/           architecture notes and the plain-English guide
```
