# Ledgerly

A small core-banking system for a Canadian community credit union: double-entry ledger,
idempotent transfers, maker-checker approvals, reversals and reconciliation.

Work in progress. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design.

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
