# Ledgerly

[![CI](https://github.com/MohamedAbdi6743/ledgerly/actions/workflows/ci.yml/badge.svg)](https://github.com/MohamedAbdi6743/ledgerly/actions/workflows/ci.yml)

A small core-banking system for a Canadian community credit union. Tellers open accounts and
handle cash, members send money to their own and other members' accounts, and managers
approve large transfers,
reverse mistakes and check that the books balance.

Built with Java, Spring Boot and PostgreSQL. The web pages (plain TypeScript) come later.

**Status:** work in progress. The schema, login, accounts, cash, and transfers are built
(milestones 1–4 of 10). Approvals and reversals come next. Each section below says what's built
and what's planned.

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
- Chequing and savings accounts with numbers from a database sequence (`100200000001`, …)
- Cash deposits and withdrawals at the counter. Each one writes two ledger entries that add up
  to zero: the member's account and "Cash on hand"
- Every money request needs an `Idempotency-Key`. A retry gets the original result back and
  moves no money; reusing a key for a different request gets a `422`
- Row locks, so two withdrawals at the same moment can't both spend the same money
- Members see only their own accounts and transactions. Anyone else's look "not found"
- Managers can freeze and unfreeze an account, with a reason that goes in the audit log
- Transfers: a member sends from their own account to any member account. Tellers can move
  money between any two member accounts
- A daily limit of $3,000 on what a member sends to other people, counted in Toronto calendar
  days. Moving money between your own accounts doesn't count
- Deadlock-free locking: the sending member first, then both accounts lowest id first
- An audit log of staff actions, password changes, and account openings and freezes
- The full ledger schema, with the money rules enforced by PostgreSQL itself

**Planned**

| Milestone | What |
|---|---|
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

All of that goes through one class, `LedgerService`. It's the only code that writes entries
or changes a balance, and it refuses any set of entries that doesn't add up to zero.

**Proof the row lock matters.** There's a test that fires 20 withdrawals of $10 at an account
holding $100, all at the same instant. Exactly 10 succeed and the balance ends at $0.00. With
the lock taken out, all 20 "succeeded": each thread read $100 and wrote back $90, so the
member got $200 in cash from a $100 account. A second test sends the same request 20 times at
once with one idempotency key. With the lock it posts once and the other 19 get the original
back. Without it, the copies race each other and crash on the database's unique key. That
still stops the double payment, but the caller gets an error instead of the original result.

**Proof the lock order matters.** Another test has Sam and Priya send each other $10, 20 times,
all at once. Every transfer finishes. With the accounts locked in request order instead of id
order, PostgreSQL reported `deadlock detected` and killed one of them. A third test fires ten
$1,000 transfers from one member at once, from two accounts to two different accounts, so they
share no account lock. Exactly three go through ($3,000). Without the lock on the member, four
went through.

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

87 tests, and GitHub Actions runs them on every push and pull request.

| Kind | Examples |
|---|---|
| Database constraints | Negative member balance, self-approval, double reversal, reused idempotency key, non-CAD currency, PostgreSQL rounding a third decimal |
| Unit tests (Mockito, fixed clock) | Entries must add up to zero, no overdraft, frozen accounts, member numbers, temporary passwords stored only as hashes |
| API integration tests (MockMvc + PostgreSQL) | Deposits and withdrawals, retries with the same key, a reused key (`422`), 3-decimal amounts, insufficient funds, frozen accounts, who can see which account, login, the temporary-password gate, audit entries |
| Concurrency (real threads, real PostgreSQL) | 20 withdrawals at once from $100; 20 copies of one request at once; Sam and Priya sending to each other at the same moment; 10 transfers at once against the daily limit |
| Security | CSRF required, roles enforced, Content-Security-Policy header, CSRF cookie readable by JavaScript (real server) |

Planned: a test that runs hundreds of random transactions and then proves the ledger still sums
to zero.

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
| POST | `/members/{id}/accounts` | teller, manager | Open a chequing or savings account |
| GET | `/members/{id}/accounts` | teller, manager | A member's accounts |
| GET | `/me/accounts` | member | My accounts |
| GET | `/accounts/{number}` | owner, staff | An account with its last 20 entries and running balance |
| PUT | `/accounts/{number}/status` | manager | Freeze or unfreeze, with a reason |
| POST | `/deposits` | teller, manager | Cash deposit (`Idempotency-Key` header required) |
| POST | `/withdrawals` | teller, manager | Cash withdrawal (`Idempotency-Key` header required) |
| POST | `/transfers` | member (from own account), teller, manager | Transfer between member accounts (`Idempotency-Key` header required) |
| GET | `/transactions/{reference}` | involved member, staff | One transaction and its entries |

A deposit, and what a retry looks like:

```http
POST /api/deposits
Idempotency-Key: 6f1c2a3e-8b0d-4c1e-9a77-2f4e1d0b9c55
{ "accountNumber": "100200000001", "amount": "200.00", "description": "Paycheque" }

201 Created
{ "reference": "TX-H28C36GS", "type": "DEPOSIT", "status": "POSTED", "amount": "200.00",
  "entries": [ { "accountNumber": "100200000001", "amount": "200.00" },
               { "accountNumber": "000000000001", "amount": "-200.00" } ], ... }
```

Sending it again with the same key returns the same body with an `Idempotent-Replayed: true`
header, and the balance doesn't change.

**Planned:** approvals, reversals, statements and reports. The full list is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#5-api-design).

Status codes: `400` invalid input, `401` not logged in, `403` wrong role, missing CSRF token or
still on a temporary password, `404` not found, `409` valid input that breaks a rule (not
enough money, frozen account), `422` an idempotency key reused for a different request.

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
- **Pessimistic locks, taken in account-id order.** Simple, and two requests can never wait on
  each other in a circle. Optimistic locking would need retry logic for the same result.
- **Look up ids, then lock.** The money code fetches only account ids first and then locks by
  id. Loading the whole account first would leave Hibernate holding a copy from before the
  lock, and the balance check could use it.
- **The daily limit locks the member, not just the accounts.** Two transfers from different
  accounts to different people touch no common account, so account locks alone wouldn't make
  them take turns. Locking the member's own row does, at the cost of one more lock per
  member transfer.
- **Idempotency checked after the locks.** A retry locks the same accounts as the original, so
  it waits for the original to commit and then finds it, instead of racing it.

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
