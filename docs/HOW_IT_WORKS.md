# How Ledgerly works

Plain-English notes on the design. The formal version, with every table and endpoint, is in
[ARCHITECTURE.md](ARCHITECTURE.md). This one is about the *why*.

Status: milestones 1 and 2 are built (database, accounts for people, login). Money movement
starts in milestone 3, so some sections below describe how it will work.

---

## What it is

Ledgerly is the back office of a small Canadian credit union. Three kinds of people use it:

- **Members** have chequing and savings accounts. They check balances and move money between
  their own accounts.
- **Tellers** work the counter. They sign up new members, open accounts, and take cash in or
  pay it out.
- **Managers** do everything a teller can, plus approve big transfers, undo mistakes, and run
  the reports that prove the books add up.

## The shape of the app

One Spring Boot program does everything. It serves the web pages and the JSON API, and it
talks to one PostgreSQL database.

Inside, the code is split into layers, and each layer only talks to the one below it:

```
controller  ->  service  ->  repository  ->  PostgreSQL
```

- A **controller** reads the HTTP request, checks the input looks right, and turns the result
  into JSON. No business rules live here.
- A **service** is where the rules are. "Is this transfer allowed?" is a service question.
- A **repository** loads and saves rows. Spring Data writes most of the SQL from method names.
- **PostgreSQL** keeps the data and has its own rules (constraints) that refuse bad data even
  if the Java code has a bug.

Splitting it this way means a rule can be tested without starting a web server, and it's always
clear where to look when something breaks.

---

## The ledger (the main idea)

### Why not just store a balance?

The obvious design is a `balance` column you add to and subtract from. The problem shows up the
first time a number looks wrong. "Why does Sam have $812.40?" There's no history to check, so
nobody can answer, and a wrong balance just stays wrong.

So Ledgerly writes down every single movement of money as rows in a `ledger_entry` table. An
account's balance is the sum of its entries. There's still a `balance` column on `account`, but
it's a cached copy for speed. A reconciliation report recalculates every balance from the
entries and flags any account where the two disagree.

### Double-entry

Money doesn't appear or vanish. It moves from one place to another. So every transaction writes
at least two entries, and they always add up to zero.

Amounts are signed: positive means money went into that account, negative means it left.

| What happened | Entries | Total |
|---|---|---|
| Sam moves $50 from chequing to savings | chequing -50.00, savings +50.00 | 0 |
| Sam deposits $200 cash at the counter | chequing +200.00, Cash on hand -200.00 | 0 |
| Sam takes out $80 cash | chequing -80.00, Cash on hand +80.00 | 0 |

Accountants would call these debits and credits. Signed numbers mean the same thing and are
easier to add up in code.

Because every transaction sums to zero, the entire ledger sums to zero. If it ever doesn't,
something is broken, and the reconciliation report says so.

### "Cash on hand"

Cash deposits need a second side too. Where did the $200 come from? From outside the system.
`Cash on hand` is a special system account that stands for the physical cash drawer. It's the
other side of every cash deposit and withdrawal.

It's the only kind of account allowed to go negative. A negative balance there just means more
cash has come in from outside than has gone out. Member accounts can never go below zero, and
the database enforces that with a `CHECK` constraint.

### Fixing mistakes

Nothing in the ledger is ever edited or deleted. If a teller types $500 instead of $50, a manager
posts a **reversal**: a new transaction with the opposite entries, linked to the original, with a
reason. Then the teller posts the right amount. Anyone reading the history later sees exactly
what happened, mistake included. Banks work this way because auditors need the full story.

---

## Making money movements safe

### Retries can't move money twice (idempotency)

Say Sam clicks Transfer and the Wi-Fi drops. The browser doesn't know if it worked, so it tries
again. Without protection, $50 moves twice.

Every money request comes with an `Idempotency-Key`, which is a random ID the page creates when
the form opens. The server saves the key with the transaction. If the same key shows up again,
the server sends back the first result instead of doing the work again. If the same key arrives
with a different amount, that's a bug or tampering, so the server refuses it.

The database backs this up with a unique constraint on (user, key). Even two identical requests
arriving in the same millisecond can only create one transaction.

### Two requests at once (locking)

An account has $100. Two $80 withdrawals arrive at the same time. Each one reads $100, decides
it's fine, and now the account is at -$60.

To stop that, the service locks the account's row (`SELECT ... FOR UPDATE`) before it checks the
balance. The second request has to wait until the first one finishes, and then it sees $20.

Transfers touch two accounts, which creates a new problem. If one transfer locks Sam then Priya,
and another locks Priya then Sam, each ends up waiting for the other forever. That's a deadlock.
The fix is simple: always lock accounts in order of their id, lowest first. Then two transfers
can never grab the locks in opposite orders.

### Big transfers need a second person (maker-checker)

Transfers and cash withdrawals of $5,000 or more don't happen right away. They're saved as
"pending approval" with no ledger entries yet. A manager has to approve or reject them, and the
person who created a request can never approve it. Banks call this the four-eyes rule. It
means one person alone can't move a large amount of money.

That rule is checked in the Java code and again in the database
(`CHECK (approved_by <> created_by)`).

---

## Money in code

Money is never a `double`. In floating point, `0.1 + 0.2` comes out as `0.30000000000000004`,
and those tiny errors add up. Ledgerly uses `BigDecimal` in Java and `NUMERIC(19,2)` in
PostgreSQL, which store exact cents.

A catch worth knowing: PostgreSQL doesn't complain if you give it three decimal places. It
quietly rounds `10.005` to `10.01`. So the Java side rejects any amount with more than two
decimals before it ever reaches the database. There's a test that shows the rounding, so the
reason for that check is written down.

The API also sends amounts as strings like `"300.00"`, not numbers, because JavaScript would
turn them into floating point.

## Time

Every money event is stored as `TIMESTAMPTZ`, an exact moment, and the app talks to the
database in UTC. That way the server's own time zone never changes what gets saved. Statements
show times in the credit union's zone (Toronto) when they're displayed.

---

## People and security (milestone 2)

### Nobody signs themselves up

A credit union has to check who you are before you get an account, so there's no public sign-up
page. Tellers create members, and managers create tellers and other managers. The very first
manager comes from settings in `.env` when the app starts, so no password is ever written in the
code.

Each member gets a member number like `M000001`. Those come from a PostgreSQL sequence instead of
"find the highest number and add one", because two tellers doing that at the same moment could
both pick the same number.

### Temporary passwords

When staff create an account, Ledgerly makes a random 14-character password, shows it once so it
can be handed over, and stores only a BCrypt hash of it. The generator uses `SecureRandom` (which
can't be predicted) and skips characters that look alike, like `0` and `O`.

Until that person picks their own password, every API call except login, logout, "who am I" and
"change password" gets a 403. That check runs before each request (`PasswordChangeInterceptor`)
and reads the flag from the database every time, so it lifts the moment the password changes.

### Logging in

Login uses a server session. After you log in, the browser holds a cookie marked `HttpOnly`,
which JavaScript can't read. Sessions beat JWT tokens here because the pages and the API live at
the same address, so cookies just work and there's less to get wrong.

Because cookies are sent automatically, another website could try to trick your browser into
sending a request (CSRF). Spring Security blocks that by giving the page a token in a cookie that
our JavaScript has to copy into a header. Another site can't read our cookies, so it can't copy
the token.

Passwords are hashed with BCrypt, which is slow on purpose so a stolen database can't be
brute-forced quickly. There's also a Content-Security-Policy header that tells the browser to
only run scripts from our own site.

### Who can do what

- The URL rules in `SecurityConfig`: only managers can create staff, and only tellers and managers
  can create or look up members.
- Rules about owning things ("a member can only see their own accounts") live in the services,
  because a URL rule can't know who owns account 100200300401. Those arrive with accounts in
  milestone 3.

### Audit log

Every staff action and password change writes a row to `audit_event`: who, what, which record,
when. The audit service uses `Propagation.MANDATORY`, which means it can only run inside the same
transaction as the change it describes. So you can't end up with a change and no audit line, or
an audit line for a change that got rolled back.

---

## Errors

Every error comes back in the same JSON shape, so the frontend handles them all one way:

```json
{ "status": 409, "error": "Conflict", "message": "An account with this email already exists.",
  "fieldErrors": {}, "timestamp": "..." }
```

- 400: the input is wrong (missing field, password too short)
- 401: not logged in
- 403: logged in but not allowed, or still on a temporary password
- 404: doesn't exist
- 409: the input is fine but it breaks a rule (email taken, not enough money)

## How it's tested

- **Database rule tests** (`LedgerSchemaTest`) try to break each constraint on purpose, like a
  negative member balance, approving your own request, or reversing something twice. Each test
  checks the *name* of the constraint that refused it, so a test can't pass by accident because
  some other rule failed. That actually caught a bug in milestone 1: the "two different accounts"
  rule was written in a way that, because of how SQL treats NULL, would have refused every
  reversal.
- **Unit tests** check service rules with fake repositories and a fixed clock. They're fast and
  need no database.
- **API tests** run the whole app against a real PostgreSQL in Docker (Testcontainers) and log in
  the way a browser does.
- **CI** runs everything on GitHub for every push and pull request.

Coming later: a test that throws hundreds of random transactions at the ledger and then checks
that everything still sums to zero, and tests where many threads hit the same account at once.

## What's next

| Milestone | What gets built |
|---|---|
| 3 | Opening accounts, cash deposits and withdrawals, the code that writes ledger entries |
| 4 | Transfers, idempotency keys, the daily limit, lock ordering |
| 5 | Approvals, reversals, more audit events |
| 6 | Statements, the reconciliation report, the large-cash report |
| 7-8 | The web pages |
| 9 | Putting a live demo online |
| 10 | Final README, review, interview prep |
