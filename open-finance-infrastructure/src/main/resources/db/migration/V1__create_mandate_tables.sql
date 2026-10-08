-- svc-pay-recurring-mandates owns these tables. Schema: sc_pay_recurring_mandates
-- (Flyway runs with it as the default schema, so names are unqualified).
-- The monolith kept VRP mandates and payments in memory only
-- (open-finance-context InMemoryVrp*Adapter), so there is nothing to backfill.

-- Mandate aggregate (VrpConsent). version is the domain's optimistic
-- concurrency token: 0 on creation, +1 per change (revocation or accepted payment).
CREATE TABLE vrp_mandate (
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

    CONSTRAINT ck_vrp_mandate_max_amount_positive CHECK (max_amount > 0),
    CONSTRAINT ck_vrp_mandate_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_vrp_mandate_status CHECK (status IN ('AUTHORISED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_vrp_mandate_revoked_at CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT ck_vrp_mandate_version CHECK (version >= 0)
);

CREATE INDEX ix_vrp_mandate_tpp ON vrp_mandate (tpp_id, created_at DESC);

COMMENT ON TABLE vrp_mandate IS 'VRP mandate aggregate (svc-pay-recurring-mandates). TPPs, PSUs and accounts are referenced by id only.';
COMMENT ON COLUMN vrp_mandate.max_amount IS 'Maximum accepted total per UTC calendar month.';
COMMENT ON COLUMN vrp_mandate.debtor_account_id IS 'Accounts-context account id, verified through ACCOUNTS_SERVICE_BASE_URL; no account data is stored.';

-- Collections accepted under a mandate. Insert-only.
CREATE TABLE vrp_payment (
    payment_id       VARCHAR(64)    PRIMARY KEY,
    consent_id       VARCHAR(64)    NOT NULL REFERENCES vrp_mandate (consent_id),
    tpp_id           VARCHAR(128)   NOT NULL,
    idempotency_key  VARCHAR(128)   NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    period_key       VARCHAR(7)     NOT NULL,
    status           VARCHAR(16)    NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_vrp_payment_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_vrp_payment_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_vrp_payment_period_key CHECK (period_key ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_vrp_payment_status CHECK (status IN ('ACCEPTED', 'REJECTED'))
);

-- The monthly-limit check sums accepted payments of one mandate and month.
CREATE INDEX ix_vrp_payment_mandate_period ON vrp_payment (consent_id, period_key) WHERE status = 'ACCEPTED';
CREATE INDEX ix_vrp_payment_tpp_idempotency ON vrp_payment (tpp_id, idempotency_key);

-- x-idempotency-key per TPP. An expired record may be replaced by a new payment.
CREATE TABLE vrp_idempotency_record (
    tpp_id           VARCHAR(128)  NOT NULL,
    idempotency_key  VARCHAR(128)  NOT NULL,
    request_hash     VARCHAR(256)  NOT NULL,
    payment_id       VARCHAR(64)   NOT NULL REFERENCES vrp_payment (payment_id),
    payment_status   VARCHAR(16)   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,

    PRIMARY KEY (tpp_id, idempotency_key),
    CONSTRAINT ck_vrp_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_vrp_idempotency_expires_at ON vrp_idempotency_record (expires_at);
