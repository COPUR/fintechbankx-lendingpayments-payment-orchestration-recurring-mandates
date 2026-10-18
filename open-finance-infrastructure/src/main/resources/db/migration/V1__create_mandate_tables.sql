-- svc-pay-recurring-mandates owns these tables. Schema: sc_pay_recurring_mandates
-- (Flyway runs with it as the default schema, so names are unqualified).
-- The monolith kept VRP mandates and payments in memory only
-- (open-finance-context InMemoryVrp*Adapter), so there is nothing to backfill.

-- Mandate aggregate (VrpConsent). version is the domain's optimistic
-- concurrency token: 0 on creation, +1 per change (revocation or accepted payment).
CREATE TABLE mandate_record (
    consent_id         VARCHAR(64)    PRIMARY KEY,
    tpp_id             VARCHAR(128)   NOT NULL,
    psu_id             VARCHAR(128)   NOT NULL,
    max_amount         NUMERIC(19, 4) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    status             VARCHAR(16)    NOT NULL,
    expires_at         TIMESTAMPTZ    NOT NULL,
    revoked_at         TIMESTAMPTZ,
    debtor_account_id  VARCHAR(64),
    version            BIGINT         NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_mandate_record_max_amount_positive CHECK (max_amount > 0),
    CONSTRAINT ck_mandate_record_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_mandate_record_status CHECK (status IN ('AUTHORISED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_mandate_record_revoked_at CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT ck_mandate_record_version CHECK (version >= 0)
);

CREATE INDEX ix_mandate_record_tpp ON mandate_record (tpp_id, created_at DESC);

COMMENT ON TABLE mandate_record IS 'VRP mandate aggregate (svc-pay-recurring-mandates). TPPs, PSUs and accounts are referenced by id only.';
COMMENT ON COLUMN mandate_record.max_amount IS 'Maximum accepted total per UTC calendar month.';
COMMENT ON COLUMN mandate_record.debtor_account_id IS 'Accounts-context account id, verified through ACCOUNTS_SERVICE_BASE_URL; no account data is stored.';

-- Collections accepted under a mandate. Insert-only.
CREATE TABLE mandate_payment (
    payment_id       VARCHAR(64)    PRIMARY KEY,
    consent_id       VARCHAR(64)    NOT NULL REFERENCES mandate_record (consent_id),
    tpp_id           VARCHAR(128)   NOT NULL,
    idempotency_key  VARCHAR(128)   NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    period_key       VARCHAR(7)     NOT NULL,
    status           VARCHAR(16)    NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_mandate_payment_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_mandate_payment_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_mandate_payment_period_key CHECK (period_key ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_mandate_payment_status CHECK (status IN ('ACCEPTED', 'REJECTED'))
);

-- The monthly-limit check sums accepted payments of one mandate and month.
CREATE INDEX ix_mandate_payment_mandate_period ON mandate_payment (consent_id, period_key) WHERE status = 'ACCEPTED';
CREATE INDEX ix_mandate_payment_tpp_idempotency ON mandate_payment (tpp_id, idempotency_key);

-- x-idempotency-key per TPP. An expired record may be replaced by a new payment.
CREATE TABLE mandate_idempotency_record (
    tpp_id           VARCHAR(128)  NOT NULL,
    idempotency_key  VARCHAR(128)  NOT NULL,
    request_hash     VARCHAR(256)  NOT NULL,
    payment_id       VARCHAR(64)   NOT NULL REFERENCES mandate_payment (payment_id),
    payment_status   VARCHAR(16)   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,

    PRIMARY KEY (tpp_id, idempotency_key),
    CONSTRAINT ck_vrp_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_vrp_idempotency_expires_at ON mandate_idempotency_record (expires_at);
