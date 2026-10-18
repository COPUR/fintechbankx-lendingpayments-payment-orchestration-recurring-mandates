-- Retryable send failures (broker, DNS or mesh-egress outages) no longer count
-- toward parking; the max-attempts cap of V2 is removed. A row is parked on a
-- retryable failure only after it has been failing continuously for longer
-- than mandates.outbox.relay.retryable-park-after (default 24 h), measured
-- from first_failed_at. Non-retriable failures still park at once.

ALTER TABLE mandate_outbox_event ADD COLUMN first_failed_at TIMESTAMPTZ;

COMMENT ON COLUMN mandate_outbox_event.first_failed_at IS 'First failed send of the row; reset to NULL with parked_at on a manual replay.';
