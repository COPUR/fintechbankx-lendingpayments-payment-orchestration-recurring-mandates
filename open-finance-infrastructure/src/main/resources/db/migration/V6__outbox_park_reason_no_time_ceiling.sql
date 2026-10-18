-- ADR-021 decision 4 (adr-runbooks #10 e6dd76a): no time-based parking. The
-- relay parks a row only for a payload error (RecordTooLarge, Serialization,
-- InvalidTopic); every other failure stops the batch without marking a row.
-- Any other row is parked only by an operator, by hand, with a reason.
-- first_failed_at (V4) measured the retired 24 h ceiling and is dropped.

ALTER TABLE mandate_outbox_event DROP COLUMN first_failed_at;
ALTER TABLE mandate_outbox_event ADD COLUMN parked_reason VARCHAR(512);

UPDATE mandate_outbox_event
   SET parked_reason = coalesce('relay: ' || last_error, 'relay: parked before V6')
 WHERE parked_at IS NOT NULL;

ALTER TABLE mandate_outbox_event
    ADD CONSTRAINT ck_outbox_parked_reason CHECK (parked_at IS NULL OR parked_reason IS NOT NULL);

COMMENT ON COLUMN mandate_outbox_event.parked_reason IS 'Why the row is parked: relay: payload error ... or operator: <ticket> <why>. Cleared with parked_at on a replay.';
