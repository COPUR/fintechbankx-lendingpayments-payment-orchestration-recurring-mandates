-- Flyway runs as the schema owner (DB_MIGRATION_USERNAME), which owns
-- sc_pay_recurring_mandates and every table in it. The service connects as the
-- runtime role (${runtime_role}, from DB_USERNAME) and gets only the DML the
-- code issues:
--   mandate_record              SELECT, INSERT, UPDATE           (aggregate; version compare-and-set, never deleted)
--   mandate_payment             SELECT, INSERT                   (accepted collections, insert-only)
--   mandate_idempotency_record  SELECT, INSERT, UPDATE, DELETE   (upsert on conflict, expiry purge)
--   mandate_outbox_event        SELECT, INSERT, UPDATE, DELETE   (relay marks, parks and purges rows)
--                               + USAGE on the created_seq identity sequence
--   dpop_proof_jti              SELECT, INSERT, UPDATE, DELETE   (replay cache upsert and purge)
-- plus USAGE on the schema. Not being the owner, it can neither CREATE, ALTER,
-- DROP nor TRUNCATE, and it cannot read flyway_schema_history.
-- Every later migration that adds a table grants the runtime role explicitly.
--
-- Local single-user runs (no DB_MIGRATION_USERNAME) migrate as the runtime
-- role itself; then there is nothing to separate and this migration only says
-- so, because revoking the owner's own privileges would break later migrations.

DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the schema owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), runtime_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC', current_schema());
    EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I', current_schema(), runtime_role);

    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE mandate_record TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE mandate_payment TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE mandate_idempotency_record, mandate_outbox_event, dpop_proof_jti TO %I',
                   runtime_role);
    EXECUTE format('GRANT USAGE ON SEQUENCE %s TO %I',
                   pg_get_serial_sequence(format('%I.mandate_outbox_event', current_schema()), 'created_seq'), runtime_role);
END
$$;
