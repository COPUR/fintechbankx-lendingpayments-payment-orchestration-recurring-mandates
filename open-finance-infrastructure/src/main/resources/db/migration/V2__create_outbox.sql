-- Transactional outbox for the evt.pay.mandate namespace. Rows are written in
-- the same transaction as the mandate change and relayed to Kafka by OutboxRelay.
-- A row that fails mandates.outbox.relay.max-attempts times is parked
-- (parked_at set): the relay skips it and an operator replays or discards it.

CREATE TABLE mandate_outbox_event (
    event_id          UUID          PRIMARY KEY,
    created_seq       BIGINT        GENERATED ALWAYS AS IDENTITY,
    aggregate_type    VARCHAR(64)   NOT NULL,
    aggregate_id      VARCHAR(64)   NOT NULL,
    aggregate_version BIGINT        NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    topic             VARCHAR(249)  NOT NULL,
    payload           JSONB         NOT NULL,
    correlation_id    VARCHAR(128)  NOT NULL,
    occurred_at       TIMESTAMPTZ   NOT NULL,
    traceparent       VARCHAR(64),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    parked_at         TIMESTAMPTZ,
    attempts          INTEGER       NOT NULL DEFAULT 0,
    last_error        VARCHAR(512),

    CONSTRAINT uq_outbox_created_seq UNIQUE (created_seq),
    CONSTRAINT ck_outbox_topic_namespace CHECK (topic LIKE 'evt.pay.mandate.%'),
    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload) = 'object')
);

-- The relay reads pending rows in insertion order.
CREATE INDEX ix_outbox_pending ON mandate_outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_published_at ON mandate_outbox_event (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX ix_outbox_parked ON mandate_outbox_event (parked_at) WHERE parked_at IS NOT NULL;

COMMENT ON TABLE mandate_outbox_event IS 'Pending, parked and recently published mandate events; published rows purged after mandates.outbox.retention.';
