# Ledgerly – Architecture Proposal

Ledgerly is a small core-banking system for a Canadian community credit union. Tellers
open accounts and handle cash. Members see their balances and transfer money. Managers
approve large transfers, reverse mistakes and check that the books balance.

Every movement of money is written as **balanced ledger entries** (double-entry), every
money request is **idempotent** (safe to retry), large transfers need **a second person to
approve them** (maker-checker), and nothing is ever edited or deleted: mistakes are fixed
with **reversals**.

This document is the plan to agree on before writing code.

---

## 0. Scope

**In version 1**
- Three roles: `MEMBER`, `TELLER`, `MANAGER`.
- Tellers create members (members don't self-register: a credit union verifies identity
  first) and open **chequing** and **savings** accounts in CAD.
- Cash deposits and withdrawals at the counter (teller).
- Transfers between accounts (member from their own accounts, or teller on their behalf).
- Rules: no overdrafts, a daily transfer limit, amounts in whole cents only,
  frozen accounts can't move money.
- Maker-checker: transfers and cash withdrawals of **$5,000 or more** wait for a manager,
  who must be a different person from whoever created them.
- Reversals by managers, with a reason. The original stays; an opposite transaction is added.
- Monthly statements with opening balance, running balance and closing balance.
- Reconciliation report: proves the whole ledger sums to zero and every stored balance
  matches its entries.
- Large cash report: cash deposits of **$10,000 or more** listed for review (in Canada,
  FINTRAC requires reporting large cash transactions; Ledgerly lists them, it doesn't file).
- Audit log of who did what, and when.
- Live demo with fictional data and demo logins for each role.

**Not in version 1:** interest, fees, holds / pending balances, real payment networks
(Interac, EFT), account closing workflow, password reset by email.

---

## 1. High-level architecture

```
 Browser (HTML + CSS + compiled TypeScript)
     │  fetch() → JSON, session cookie, CSRF header, Idempotency-Key header
     ▼
 Spring Boot application (one process)
     Spring Security ─ who is this? which role? 
     Controllers ───── HTTP in / JSON out, @Valid request DTOs
     Services ──────── money rules, transactions, row locks, idempotency
     Repositories ──── Spring Data JPA (+ a few SQL queries for reports)
     │
     ▼
 PostgreSQL ─ accounts, journal transactions, ledger entries, approvals, audit log
```

Same shape as HandsOn: one application, layered **Controller → Service → Repository →
PostgreSQL**, serving its own pages so the browser talks to one address (no CORS, cookies
just work). Microservices would add network calls and failure points without solving any
problem at this size.

---

## 2. Core idea: the ledger

### 2.1 Why not just a `balance` column?

The naive design stores a balance and does `balance = balance - 50`. After a bug, a
crash or a disputed charge, nobody can answer *"why is this balance $812.40?"* There's no
history to check, and a wrong number simply stays wrong.

Ledgerly stores **every movement** as ledger entries. A balance is the sum of an account's
entries. The `account.balance` column is kept as a fast copy (updated in the same database
transaction), and the reconciliation report proves the copy is right.

### 2.2 Double-entry, in plain terms

Money never appears or disappears; it always moves **from** somewhere **to** somewhere.
So every journal transaction has two or more entries whose amounts **add up to zero**.

Ledgerly uses **signed amounts**: positive = money into that account, negative = money out.
(Accountants say "debit" and "credit"; signed amounts mean the same thing and are easier to
read in code. The README will show how they map.)

| Action | Entries | Sum |
|---|---|---|
| Sam transfers $50 from chequing to savings | Sam chequing **−50.00**, Sam savings **+50.00** | 0 |
| Teller takes a $200 cash deposit for Sam | Sam chequing **+200.00**, *Cash on hand* **−200.00** | 0 |
| Teller pays out $80 cash to Sam | Sam chequing **−80.00**, *Cash on hand* **+80.00** | 0 |

*Cash on hand* is a **system account** representing the physical cash drawer: money coming
in from (or going out to) the outside world. It's allowed to be negative; member accounts
never are.

### 2.3 Correcting mistakes: reversals

A $500 deposit was meant to be $50. Nobody edits it. A manager posts a **reversal**: a new
transaction with the opposite entries (−500 / +500), linked to the original and with a
reason. Then the teller posts the correct $50. The history shows exactly what happened.

### 2.4 Idempotency: safe retries

A member clicks "Transfer" and the Wi-Fi drops. Did it go through? The app retries. Without
protection, the money moves twice.

Every money request carries an `Idempotency-Key` header (a random ID the browser creates
when the form opens). The server stores it with the transaction, unique per user:
- **Same key, same request** → return the original result. Nothing moves again.
- **Same key, different request** (e.g. a different amount) → refuse with `422`.
- **Two identical requests at the same instant** → the database's unique constraint lets
  only one insert win; the other returns the winner's result.

### 2.5 Concurrency: two transfers, one account

Two withdrawals of $80 hit an account holding $100 at the same moment. Each reads $100 and
says yes: −$60. Ledgerly locks the account rows (`SELECT … FOR UPDATE`) before checking
the balance, so the second request waits and then sees $20.

A transfer locks **two** accounts. If transfer A locks Sam then Priya while transfer B locks
Priya then Sam, each waits for the other forever (a **deadlock**). Ledgerly always locks
accounts **in order of their id**, so that can't happen. A test runs A→B and B→A
transfers at the same time to prove it.

The database backs this up: `CHECK (type = 'SYSTEM' OR balance >= 0)` refuses a negative
member balance even if the Java code had a bug.

### 2.6 Maker-checker

A transfer of $5,000 or more isn't posted immediately. It's saved as `PENDING_APPROVAL`
with no ledger entries. A manager approves (the balance is checked again at that moment,
and entries are written) or rejects it with a reason. The system refuses an approval by the
same person who created it, whatever their role.

---

## 3. Backend architecture

Layers work exactly as in HandsOn:

| Layer | Belongs here | Doesn't belong here |
|---|---|---|
| Controller | URLs, `@Valid` DTOs, reading the `Idempotency-Key` header, status codes | Rules, SQL |
| Service | Money rules, locking, idempotency, maker-checker, `@Transactional` | HTTP details |
| Repository | Spring Data queries, `@Lock(PESSIMISTIC_WRITE)` loads, report SQL | Rules |
| Database | Constraints (no negative member balance, unique keys, valid statuses) | Logic |

Key services, each small and focused:
- `LedgerService` – the only code that writes ledger entries. Takes a list of
  (account, amount) pairs, checks they sum to zero, updates the cached balances, records
  `balance_after` on each entry.
- `PaymentService` – deposits, withdrawals, transfers: validates, locks, applies rules,
  decides "post now" or "needs approval", calls `LedgerService`.
- `IdempotencyService` – finds a previous result for (user, key) and compares request
  fingerprints.
- `ApprovalService`, `ReversalService`, `StatementService`, `ReconciliationService`,
  `AuditService`.

**Money in Java:** always `BigDecimal` with scale 2, never `double`
(`0.1 + 0.2 = 0.30000000000000004` in floating point). Amounts with more than 2 decimal
places are rejected at validation, so Ledgerly never has to round. In PostgreSQL:
`NUMERIC(19,2)`.

Packages by layer (`controller`, `service`, `repository`, `entity`, `dto`, `exception`,
`config`), like HandsOn.

---

## 4. Database design

```
 app_user 1 ──< account            (a member owns accounts; system accounts have no owner)
 app_user 1 ──< journal_transaction  (created_by, approved_by)
 journal_transaction 1 ──< ledger_entry >── 1 account
 journal_transaction 0..1 ── reverses ── journal_transaction
 app_user 1 ──< audit_event
```

**`app_user`** – members and staff

| Column | Type | Rules |
|---|---|---|
| id | `BIGINT` identity | PK |
| email | `VARCHAR(255)` | unique, lowercase |
| password_hash | `VARCHAR(100)` | BCrypt |
| full_name | `VARCHAR(100)` | not null |
| role | `VARCHAR(20)` | `MEMBER`, `TELLER`, `MANAGER` |
| member_number | `VARCHAR(12)` | unique, members only |
| created_at | `TIMESTAMPTZ` | not null |

**`account`**

| Column | Type | Rules |
|---|---|---|
| id | `BIGINT` identity | PK |
| account_number | `VARCHAR(12)` | unique, shown to people instead of the id |
| owner_id | `BIGINT` | FK → app_user, null for system accounts |
| type | `VARCHAR(20)` | `CHEQUING`, `SAVINGS`, `SYSTEM` |
| name | `VARCHAR(60)` | e.g. "Everyday chequing", "Cash on hand" |
| currency | `CHAR(3)` | `CHECK (currency = 'CAD')` for now |
| balance | `NUMERIC(19,2)` | cached sum of entries; `CHECK (type = 'SYSTEM' OR balance >= 0)` |
| status | `VARCHAR(20)` | `OPEN`, `FROZEN` |
| created_at | `TIMESTAMPTZ` | not null |

**`journal_transaction`** – one business event (a transfer, a deposit, a reversal)

| Column | Type | Rules |
|---|---|---|
| id | `BIGINT` identity | PK |
| reference | `VARCHAR(20)` | unique, shown on statements (e.g. `TX-7F3K9Q2M`) |
| type | `VARCHAR(20)` | `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `REVERSAL` |
| status | `VARCHAR(20)` | `POSTED`, `PENDING_APPROVAL`, `REJECTED` |
| amount | `NUMERIC(19,2)` | `CHECK (amount > 0)` |
| from_account_id / to_account_id | `BIGINT` | FK → account (the request, kept for pending items) |
| description | `VARCHAR(140)` | memo shown on statements |
| created_by | `BIGINT` | FK → app_user |
| approved_by | `BIGINT` | FK → app_user, nullable; `CHECK (approved_by <> created_by)` |
| decision_reason | `VARCHAR(200)` | rejection or reversal reason |
| reverses_id | `BIGINT` | FK → journal_transaction, `UNIQUE` (a transaction can be reversed once) |
| idempotency_key | `VARCHAR(64)` | `UNIQUE (created_by, idempotency_key)` |
| request_fingerprint | `VARCHAR(64)` | hash of the request body, to detect key reuse |
| created_at / posted_at | `TIMESTAMPTZ` | |

**`ledger_entry`** – append-only; never updated or deleted

| Column | Type | Rules |
|---|---|---|
| id | `BIGINT` identity | PK |
| transaction_id | `BIGINT` | FK → journal_transaction |
| account_id | `BIGINT` | FK → account |
| amount | `NUMERIC(19,2)` | `CHECK (amount <> 0)`, signed |
| balance_after | `NUMERIC(19,2)` | account balance right after this entry (statements use it) |
| created_at | `TIMESTAMPTZ` | |

Indexes: `(account_id, created_at)` for statements, `(transaction_id)`.
"Entries of a transaction sum to zero" is enforced in `LedgerService`, tested, and checked
by reconciliation. (A database trigger could also enforce it; listed as an alternative.)

**`audit_event`** – append-only: actor, action (`ACCOUNT_OPENED`, `ACCOUNT_FROZEN`,
`TRANSFER_APPROVED`, …), entity, details, timestamp.

**Why `TIMESTAMPTZ` here** (HandsOn used local time): money events need an exact,
unambiguous moment for statements and audits, and a deployed server may run in UTC.
Statements convert to the credit union's time zone (`America/Toronto`) for display.

**Sample data**

```
account
 1  000000000001  SYSTEM    Cash on hand        balance  -1,250.00
 2  100200300401  CHEQUING  Sam Ortiz           balance     950.00
 3  100200300402  SAVINGS   Sam Ortiz           balance     300.00

journal_transaction
 TX-A1  DEPOSIT   POSTED  1,250.00   created_by teller Dana
 TX-B2  TRANSFER  POSTED    300.00   created_by Sam

ledger_entry
 TX-A1  Sam chequing  +1,250.00  after 1,250.00
 TX-A1  Cash on hand  -1,250.00  after -1,250.00
 TX-B2  Sam chequing    -300.00  after   950.00
 TX-B2  Sam savings     +300.00  after   300.00
                         sum of all = 0.00 ✓
```

Schema managed by Flyway migrations; Hibernate set to `validate`.

---

## 5. API design

All under `/api`, JSON, same error format as HandsOn
(`{ status, error, message, fieldErrors, timestamp }`).
Money endpoints **require** an `Idempotency-Key` header (missing → `400`).

| Method | Path | Who | Purpose | Success | Main errors |
|---|---|---|---|---|---|
| POST | `/auth/login` · `/auth/logout` | anyone / logged in | Session login / logout | 200 / 204 | 401 |
| GET | `/auth/me` · `/auth/csrf` | logged in / anyone | Current user / CSRF cookie | 200 / 204 | 401 |
| POST | `/auth/password` | logged in | Change own password (12–72 characters) | 204 | 400 wrong current password |
| POST | `/staff` | manager | Create a teller or manager (temporary password) | 201 | 400, 409 email taken |
| POST | `/members` | teller, manager | Create a member (temporary password) | 201 | 400, 409 email taken |
| GET | `/members?query=` · `/members/{id}` | teller, manager | Search / view a member | 200 | 404 |
| POST | `/members/{id}/accounts` | teller, manager | Open chequing or savings | 201 | 404 |
| GET | `/me/accounts` | member | My accounts and balances | 200 | |
| GET | `/accounts/{number}` | owner, staff | Account with recent activity | 200 | 403, 404 |
| PUT | `/accounts/{number}/status` | manager | Freeze / unfreeze | 200 | 404 |
| POST | `/deposits` | teller | Cash deposit | 201 | 400, 409 frozen |
| POST | `/withdrawals` | teller | Cash withdrawal | 201 posted / 202 pending | 409 insufficient funds |
| POST | `/transfers` | member (own accounts), teller | Transfer | 201 posted / 202 pending | 409 funds, limit, frozen |
| GET | `/transactions/{reference}` | involved member, staff | One transaction with its entries | 200 | 404 |
| GET | `/approvals` | manager | Pending queue | 200 | |
| POST | `/approvals/{reference}/approve` · `/reject` | manager (not the creator) | Decide | 200 | 403 own request, 409 no longer enough funds |
| POST | `/transactions/{reference}/reversal` | manager | Reverse with reason | 201 | 409 already reversed / would overdraw |
| GET | `/accounts/{number}/statement?month=2026-11` | owner, staff | Monthly statement | 200 | 400 bad month |
| GET | `/reports/reconciliation` | manager | Ledger proof | 200 | |
| GET | `/reports/large-cash?from=&to=` | manager | Cash deposits ≥ $10,000 | 200 | |
| GET | `/audit?entityType=&entityId=` | manager | Audit trail | 200 | |

**Example: a transfer**

```http
POST /api/transfers
Idempotency-Key: 6f1c2a3e-8b0d-4c1e-9a77-2f4e1d0b9c55
{ "fromAccount": "100200300401", "toAccount": "100200300402",
  "amount": "300.00", "description": "Rent savings" }

201 Created
{ "reference": "TX-B2", "type": "TRANSFER", "status": "POSTED", "amount": "300.00",
  "entries": [ { "account": "100200300401", "amount": "-300.00", "balanceAfter": "950.00" },
               { "account": "100200300402", "amount": "300.00",  "balanceAfter": "300.00" } ] }
```

Sending the same request again with the same key returns the same body (and an
`Idempotent-Replayed: true` header). Amounts are sent as **strings** so JavaScript never
turns them into floating-point numbers.

**Validation:** amount > 0, at most 2 decimal places, at most $1,000,000.00; accounts must
differ; description ≤ 140 characters. Rules (`409`): sufficient funds, daily limit
($3,000 per member, configurable), accounts open, member owns the source account.

---

## 6. Frontend

Plain TypeScript, multi-page, same pattern as HandsOn (`api.ts`, `auth.ts`, `dom.ts`,
one small file per page, real stylesheet, `textContent` only, CSP header).

| Page | Who | What it shows |
|---|---|---|
| Login | all | Plus demo-login buttons on the live demo |
| My accounts | member | Balances, recent activity, "Transfer" |
| Account | member, staff | History with running balance; link to statements |
| Transfer | member, teller | Form; creates an idempotency key when it opens |
| Statement | member, staff | Printable monthly statement |
| Members | teller | Search, create member, open account |
| Cash | teller | Deposit / withdrawal for a member account |
| Approvals | manager | Queue with approve / reject |
| Transaction | staff | Entries, approval info, "Reverse" for managers |
| Reconciliation · Large cash · Audit | manager | Reports |

The transfer page makes idempotency visible: the key is created once per form, so a
double-click or a retry after a network error sends the same key, and the page shows
"Already submitted – this is the same transfer" instead of a duplicate.

---

## 7. Testing plan

- **Unit (Mockito, frozen clock):** daily limit at the edge, maker-checker threshold,
  fingerprint mismatch, approval by the creator refused.
- **API integration (MockMvc + PostgreSQL in Testcontainers):** every rule and status code,
  role checks, statement math.
- **Invariant test:** run a few hundred random deposits, withdrawals, transfers and
  reversals (seeded random generator), then assert the ledger sums to zero, every cached
  balance equals its entries, and no member balance is negative.
- **Concurrency:** 20 threads withdrawing from one account can't overdraw it; A→B and B→A
  transfers at once both finish (no deadlock); 10 identical requests with one idempotency
  key move money exactly once.
- **Database constraints:** negative member balance, duplicate idempotency key, self-approval
  and double reversal are refused by PostgreSQL itself.
- **Security:** CSRF required, roles enforced, members can't see other members' accounts,
  login rate limiting.

---

## 8. Design decisions

**D1. Ledger entries plus a cached balance** (not balance-only, not entries-only).
Entries give history and provability; the cached balance gives a fast read and a row to
lock. Reconciliation checks they agree. *If it grew:* periodic balance snapshots so
statements never scan years of entries.

**D2. Signed amounts instead of debit/credit columns.** Easier to read and sum; the
accounting mapping is documented. *Trade-off:* accountants may expect debit/credit columns.

**D3. Pessimistic locks, ordered by account id.** Simple, correct, and deadlock-free.
*Alternative:* optimistic `@Version` with retries. *Trade-off:* locked rows wait briefly,
which is fine for a credit union.

**D4. Idempotency keys stored on the transaction row.** One table, one unique constraint,
no extra infrastructure. *Alternative:* a separate key store or Redis (not needed here).
*If it grew:* expire old keys after a retention period.

**D5. Pending transfers write no entries until approved.** Simple and clearly correct.
*Trade-off:* funds aren't held while waiting, so the balance is re-checked at approval and
can fail then. *If it grew:* "available balance" with holds.

**D6. Members are created by staff, not self-registered.** Matches how a credit union
verifies identity. New members and staff get a temporary password (shown once, stored only
as a BCrypt hash) and must change it before using anything else: a check that runs before
every `/api` request (except `/api/auth/**`) returns `403` until they do. It reads the flag
from the database each time, so the change takes effect immediately. Member numbers come
from a PostgreSQL sequence so two tellers can never hand out the same one.

**D7. `TIMESTAMPTZ` for money events** (see the database section).

**D8. Deployment as one Docker image** (TypeScript compiled and the Spring Boot jar built
inside it) on a hosted service with managed PostgreSQL. Exact provider chosen at that
milestone after checking current free tiers. Demo data is fictional and resettable;
secrets live in the host's environment variables.

---

## 9. Milestones (about 8 weeks at 5–10 hours a week)

1. **Setup:** Spring Boot, PostgreSQL in Docker, Flyway V1, CI from day one.
2. **Users and security:** roles, staff-created members, first manager from settings,
   CSRF, error handling.
3. **Accounts and the ledger core:** system accounts, `LedgerService`, cash deposits and
   withdrawals with row locks.
4. **Transfers and idempotency:** keys, fingerprints, daily limit, ordered locking, the
   concurrency tests.
5. **Maker-checker, reversals and audit log.**
6. **Statements, reconciliation and the large cash report;** the invariant test.
7. **Frontend: member pages.**
8. **Frontend: staff pages.**
9. **Deployment:** Docker image, hosting, demo data, login rate limiting.
10. **Docs and review:** README with screenshots and diagram, hiring-manager review,
    interview prep.
