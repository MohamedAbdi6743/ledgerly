# Ledgerly

A small core-banking system for a Canadian community credit union: double-entry ledger,
idempotent transfers, maker-checker approvals, reversals and reconciliation.

Work in progress: accounts for people and login are built; moving money comes next.

## How it works

- **Every movement of money is written down.** An account's balance is the sum of its ledger
  entries, so any number can be explained. The `balance` column is only a cached copy, and a
  reconciliation report checks that it matches.
- **Double-entry.** Each transaction writes at least two entries that add up to zero
  (chequing -50, savings +50). Cash deposits use a "Cash on hand" account as the other side.
- **Nothing is edited or deleted.** Mistakes are fixed with a reversal: a new, opposite
  transaction with a reason.
- **Retries can't move money twice.** Every money request carries an idempotency key, and the
  same key returns the first result instead of repeating the transfer.
- **No overdrafts, even with requests arriving at once.** Account rows are locked before the
  balance is checked, always in id order so two transfers can't deadlock.
- **Big transfers need a second person.** $5,000 or more waits for a manager, and nobody can
  approve their own request.
- **Exact money.** `BigDecimal` and `NUMERIC(19,2)`, never `double`.
- **Staff create accounts.** No public sign-up; new accounts get a temporary password that must
  be changed before anything else works. Staff actions go to an audit log.
- **The database enforces the rules too.** No negative member balance, no self-approval, no
  double reversal, one use per idempotency key.

The plain-English walkthrough is in [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md). Tables,
endpoints and design trade-offs are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tech stack

Java 21, Spring Boot 4, Spring Data JPA, PostgreSQL 18, Flyway, JUnit 5, Testcontainers.

## Running locally

You need Java 21 and Docker. Maven isn't required; the project includes the Maven Wrapper.

1. Create your local settings file, then open it and set your own passwords:

   ```bash
   cp .env.example .env
   ```

   `LEDGERLY_ADMIN_EMAIL` and `LEDGERLY_ADMIN_PASSWORD` create the first manager when the app
   starts. Every other account (tellers, managers, members) is created by staff and gets a
   temporary password that must be changed at first login.

2. Start PostgreSQL:

   ```bash
   docker compose up -d
   ```

3. Start the app (Flyway creates the tables on first start):

   ```bash
   ./mvnw spring-boot:run
   ```

   On Windows PowerShell use `.\mvnw.cmd spring-boot:run`.

## Running tests

Docker must be running. The tests start their own temporary PostgreSQL container.

```bash
./mvnw test
```
