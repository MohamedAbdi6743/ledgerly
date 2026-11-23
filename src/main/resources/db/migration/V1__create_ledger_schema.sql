-- Ledgerly schema. The database enforces every rule it can on its own, so a bug in the
-- Java code can't corrupt money data. Times are TIMESTAMPTZ: exact moments, no time-zone guessing.

-- Members and staff. "user" is a reserved word in PostgreSQL, hence app_user.
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    member_number VARCHAR(12)  UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT app_user_role_check  CHECK (role IN ('MEMBER', 'TELLER', 'MANAGER')),
    CONSTRAINT app_user_email_lower CHECK (email = LOWER(email)),
    -- Members have a member number; staff don't.
    CONSTRAINT app_user_member_number CHECK ((role = 'MEMBER') = (member_number IS NOT NULL))
);

-- Chequing and savings accounts owned by members, plus SYSTEM accounts like "Cash on hand".
CREATE TABLE account (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_number VARCHAR(12)   NOT NULL UNIQUE,
    owner_id       BIGINT        REFERENCES app_user (id),
    type           VARCHAR(20)   NOT NULL,
    name           VARCHAR(60)   NOT NULL,
    currency       CHAR(3)       NOT NULL DEFAULT 'CAD',
    -- A cached copy of the sum of this account's ledger entries, updated in the same
    -- database transaction. The reconciliation report proves it's right.
    balance        NUMERIC(19,2) NOT NULL DEFAULT 0,
    status         VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT account_type_check     CHECK (type IN ('CHEQUING', 'SAVINGS', 'SYSTEM')),
    CONSTRAINT account_status_check   CHECK (status IN ('OPEN', 'FROZEN')),
    CONSTRAINT account_currency_check CHECK (currency = 'CAD'),
    -- System accounts have no owner; member accounts always do.
    CONSTRAINT account_owner_check    CHECK ((type = 'SYSTEM') = (owner_id IS NULL)),
    -- No overdrafts: a member account can never go below zero, even if the Java code had a bug.
    CONSTRAINT account_no_overdraft   CHECK (type = 'SYSTEM' OR balance >= 0)
);

CREATE INDEX account_owner_idx ON account (owner_id);

-- One business event: a deposit, withdrawal, transfer or reversal.
CREATE TABLE journal_transaction (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference           VARCHAR(20)   NOT NULL UNIQUE,
    type                VARCHAR(20)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    amount              NUMERIC(19,2) NOT NULL,
    from_account_id     BIGINT        REFERENCES account (id),
    to_account_id       BIGINT        REFERENCES account (id),
    description         VARCHAR(140),
    created_by          BIGINT        NOT NULL REFERENCES app_user (id),
    approved_by         BIGINT        REFERENCES app_user (id),
    decision_reason     VARCHAR(200),
    reverses_id         BIGINT        UNIQUE REFERENCES journal_transaction (id),
    idempotency_key     VARCHAR(64)   NOT NULL,
    request_fingerprint VARCHAR(64)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    posted_at           TIMESTAMPTZ,

    CONSTRAINT journal_type_check   CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    CONSTRAINT journal_status_check CHECK (status IN ('POSTED', 'PENDING_APPROVAL', 'REJECTED')),
    CONSTRAINT journal_amount_positive CHECK (amount > 0),
    -- Only checked when both accounts are set (deposits, withdrawals and reversals leave one or
    -- both empty). Careful: in SQL, NULL IS DISTINCT FROM NULL is false, so a simpler-looking
    -- "from IS DISTINCT FROM to" would wrongly refuse every reversal.
    CONSTRAINT journal_different_accounts CHECK (
        from_account_id IS NULL OR to_account_id IS NULL OR from_account_id <> to_account_id),
    -- Maker-checker: nobody approves their own request.
    CONSTRAINT journal_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    -- A reversal always points at what it reverses, and only reversals do.
    CONSTRAINT journal_reversal_link CHECK ((type = 'REVERSAL') = (reverses_id IS NOT NULL)),
    -- Idempotency: the same person can't use the same key twice.
    CONSTRAINT journal_idempotency UNIQUE (created_by, idempotency_key)
);

CREATE INDEX journal_status_idx ON journal_transaction (status);

-- The ledger itself. Append-only: rows are inserted, never updated or deleted.
-- The entries of one journal transaction always sum to zero (double-entry).
CREATE TABLE ledger_entry (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT        NOT NULL REFERENCES journal_transaction (id),
    account_id     BIGINT        NOT NULL REFERENCES account (id),
    amount         NUMERIC(19,2) NOT NULL,
    balance_after  NUMERIC(19,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entry_nonzero CHECK (amount <> 0)
);

CREATE INDEX ledger_entry_account_time_idx ON ledger_entry (account_id, created_at);
CREATE INDEX ledger_entry_transaction_idx ON ledger_entry (transaction_id);

-- Who did what, and when. Append-only.
CREATE TABLE audit_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    BIGINT       REFERENCES app_user (id),
    action      VARCHAR(40)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   VARCHAR(40)  NOT NULL,
    details     VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX audit_event_entity_idx ON audit_event (entity_type, entity_id);

-- The cash drawer: the other side of every cash deposit and withdrawal.
INSERT INTO account (account_number, owner_id, type, name)
VALUES ('000000000001', NULL, 'SYSTEM', 'Cash on hand');
