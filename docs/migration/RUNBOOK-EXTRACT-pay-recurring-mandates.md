# RUNBOOK-EXTRACT-pay-recurring-mandates

Extraction of variable recurring payment mandates (VRP consents) and their
collections from `enterprise-loan-management-system` (`open-finance-context`,
package `com.enterprise.openfinance.recurringpayments`) into
`svc-pay-recurring-mandates` (this repository), following the strangler-fig
steps of `fbx-monolith-extraction`. Status: **Proposed**.

| Field | Value |
|---|---|
| Context / service | `pay` / `svc-pay-recurring-mandates` (Helm/SA `payment-recurring-mandates-service`, namespace `payments`) |
| Slice | Mandate aggregate (`VrpConsent`): authorise, read, revoke; collections (`VrpPayment`) under its monthly limit |
| Owned data | `db_pay_recurring_mandates_<env>`, schema `sc_pay_recurring_mandates`: `mandate_record`, `mandate_payment`, `mandate_idempotency_record`, `mandate_outbox_event`, `dpop_proof_jti` (DPoP replay cache) |
| Events | `evt.pay.mandate.created.v1`, `evt.pay.mandate.revoked.v1`, `evt.pay.mandate.payment-accepted.v1` (`Payments.Mandate.{Created,Revoked,PaymentAccepted}.v1`) (no DLQ here: dead-letter topics belong to consumers, ADR-019/024); contract `api/asyncapi/svc-pay-recurring-mandates.yaml` (catalog PR pending) |
| Depends on | Keycloak realm `fintechbankx` (TPP tokens with `aud` = service id and DPoP binding `cnf.jkt`, so TPP clients must be DPoP-enabled before cutover; client-credentials client `svc-pay-recurring-mandates`); consent-authorization-service `GET /api/v1/consents/{id}` (`CONSENT_SERVICE_BASE_URL`; this service must be on its allow-list, which the provider branch already has) for the PSU-authorised consent every mandate is bound to; accounts API `GET /api/v1/accounts/{accountId}` (`ACCOUNTS_SERVICE_BASE_URL` + `ACCOUNTS_SERVICE_PATH`; interim, no provider yet, see checklist) for the debtor account |

## 1. Data ownership split

The monolith never persisted this capability. Its only adapters were
`InMemoryVrpConsentAdapter`, `InMemoryVrpPaymentAdapter`,
`InMemoryVrpIdempotencyAdapter`, `InMemoryVrpCacheAdapter` and
`InMemoryVrpLockAdapter`; no Flyway migration under
`src/main/resources/db/migration` or `open-finance-context/.../db/migration`
mentions VRP, mandates or recurring payments
(`open_finance.payment_transaction` / `payment_idempotency` belong to
single-payment initiation, not to VRP).

Consequences:
- **No backfill and no reconciliation job.** Mandates held by a running
  monolith instance live only in its memory and are lost on restart anyway.
  TPPs re-create mandates against the new API after cutover.
- No `db/backfill`, no `scripts/migration/verify-backfill.sh` and no
  data-split CI job in this repository, on purpose.
- Flyway migrations: `open-finance-infrastructure/src/main/resources/db/migration/V1__create_mandate_tables.sql`,
  `V2__create_outbox.sql`, `V3__create_dpop_proof_jti.sql`, `V4__outbox_first_failed_at.sql`. The service never reads monolith tables; nothing
  else may read `sc_pay_recurring_mandates`.

## 2. Cutover plan (routing only)

| Step | Action | Rollback |
|---|---|---|
| 1 | DBA bootstrap (section "Database roles" below): create the schema owner `pay_recurring_mandates_owner` and the runtime role `pay_recurring_mandates_app`, write their credentials to `<env>/payment-recurring-mandates-service/db-migration` and `<env>/payment-recurring-mandates-service/db-app` (ESO may read only `<env>/<service account>/`). Set `config.DB_URL` to the Terraform output `jdbc_url` (`sslmode=verify-full&sslrootcert=/etc/ssl/rds/global-bundle.pem`; the chart refuses anything else and mounts ConfigMap `rds-ca-bundle`, which trust-manager must have published in `payments`), `migration.remoteSecretName` to `migration_db_secret_name`. Deploy with `OUTBOX_RELAY_ENABLED=false`; the pre-install hook Job runs Flyway as the owner (V1 to V5) before the pods start. | uninstall the chart; drop the schema |
| 2 | Mesh repository adds the ALLOW rule for the ingress gateway principal `cluster.local/ns/istio-ingress/sa/istio-ingressgateway` on `payment-recurring-mandates-service` (no internal callers today). | remove the rule |
| 3 | Route `/open-finance/v1/vrp/**` at the ingress gateway from the monolith to this service; announce to TPPs that mandates must be re-created. | route back to the monolith (its in-memory state was empty after any restart, so nothing is lost either way) |
| 4 | Once `evt.pay.mandate.*.v1` exist in the platform topic catalog: `OUTBOX_RELAY_ENABLED=true`. Events written since step 1 are relayed in order. | relay off; events stay in the outbox |
| 5 | Remove `recurringpayments` from the monolith (`open-finance-context`). | revert the removal commit |

Rollback triggers (any one, measured over 15 minutes after a step): 5xx rate on
`/open-finance/v1/vrp/**` above 1 %; p99 latency above 1 s; `outbox_parked_events`
above 0; 401 rate with `invalid_dpop_proof` above 5 % of VRP calls (TPPs not DPoP-ready); `outbox_oldest_pending_age_seconds` above 300 with the relay enabled.

### Database roles

| Role | Secret | Used by | Rights |
|---|---|---|---|
| `pay_recurring_mandates_owner` | `<env>/payment-recurring-mandates-service/db-migration` | Helm pre-install/pre-upgrade Job `payment-recurring-mandates-service-db-migration` (image with `migrate`), deleted with its ExternalSecret when it succeeds | owns `sc_pay_recurring_mandates` and every table (DDL) |
| `pay_recurring_mandates_app` (`DB_USERNAME`) | `<env>/payment-recurring-mandates-service/db-app` | the service pods (`SPRING_FLYWAY_ENABLED=false`) | USAGE on the schema; `mandate_record` SELECT/INSERT/UPDATE; `mandate_payment` SELECT/INSERT; `mandate_idempotency_record`, `mandate_outbox_event`, `dpop_proof_jti` SELECT/INSERT/UPDATE/DELETE; USAGE on the outbox `created_seq` sequence (V5). No DDL, no TRUNCATE, no Flyway history |

DBA bootstrap, once per environment, as the RDS master user (`master_user_secret_arn`):

```sql
CREATE ROLE pay_recurring_mandates_owner LOGIN PASSWORD '<from a password generator>';
CREATE ROLE pay_recurring_mandates_app LOGIN PASSWORD '<from a password generator>';
GRANT CONNECT ON DATABASE db_pay_recurring_mandates_<env> TO pay_recurring_mandates_owner, pay_recurring_mandates_app;
GRANT CREATE ON DATABASE db_pay_recurring_mandates_<env> TO pay_recurring_mandates_owner;  -- Flyway creates the schema
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
```

Then put `{"username","password"}` of each role into its secret
(`aws secretsmanager put-secret-value`). Flyway creates `sc_pay_recurring_mandates` as
the owner and V5 grants the runtime role; a later migration that adds a table must grant
it explicitly. Pending on the platform side, not worked around here: microservice-base
(terraform-modules, ref=main) still names its runtime secret `<env>-<slug>/runtime`
and uses `timestamp()` in tags; Platform fixes both in terraform-modules #11.

## 3. Acceptance checklist

- [x] Service builds and tests standalone (`./gradlew check`, including PostgreSQL integration tests with `TEST_DB_URL`)
- [x] Own schema and migrations; Hibernate validates entities at startup
- [x] Flyway as the schema owner in a Helm hook Job; pods run as a DML-only role (IT proves the runtime role cannot run DDL)
- [x] Events written through a transactional outbox, relayed in order with one active relay; permanent failures parked at once, retryable failures parked only after 24 h of continuous failure
- [x] Idempotent collections (`x-idempotency-key`, unique per TPP in the database, race-tested)
- [x] Monthly limit enforced under concurrency (advisory lock per mandate plus version compare-and-set)
- [x] Debtor account check through the accounts API with a service token, failing closed
- [ ] An accounts API serving `GET /api/v1/accounts/{accountId}` to services (interim gap: the monolith has no internal account-status read; its only account read is the TPP-facing AIS `GET /open-finance/v1/accounts/{accountId}` in `open-finance-context`, which needs a PSU AIS consent, a DPoP-bound TPP token and returns `Data.Account.Status` without a debit flag; it is being extracted to svc-of-personal-financial-data. `ACCOUNTS_SERVICE_BASE_URL` and `ACCOUNTS_SERVICE_PATH` are configurable; until a provider exists, mandates naming a debtor account fail closed with 503)
- [ ] Topics `evt.pay.mandate.*.v1` in the platform topic catalog and AsyncAPI catalog PR merged
- [ ] Mesh ALLOW rule for the ingress gateway (mesh repository)
- [ ] Ingress route switched; monolith `recurringpayments` removed

## 4. Parked outbox events

The relay has no attempt cap. A retryable Kafka failure (any `RetriableException`
such as `TimeoutException`, `NotEnoughReplicasException`, `NetworkException`, or the
relay's own send timeout) stops the batch and is retried on the next run; it parks
the row only when that row has been failing continuously for longer than
`mandates.outbox.relay.retryable-park-after` (`OUTBOX_RELAY_RETRYABLE_PARK_AFTER`,
default `PT24H`), measured from its `first_failed_at` (V4). An ordinary broker or
egress outage therefore parks nothing; it shows as a growing
`outbox_oldest_pending_age_seconds`. A permanent failure (`RecordTooLargeException`,
`SerializationException`, `TopicAuthorizationException`, `InvalidTopicException`,
anything not retriable) parks the row at once and the batch continues.

`outbox_parked_events` above 0 means a consumer is missing an event. Find the rows:

```sql
SELECT event_id, created_seq, topic, aggregate_id, attempts, first_failed_at, last_error, parked_at
FROM sc_pay_recurring_mandates.mandate_outbox_event
WHERE parked_at IS NOT NULL
ORDER BY created_seq;
```

Fix the cause (topic ACL, topic missing, payload size), then replay. Reset
`first_failed_at` as well, otherwise the 24 h ceiling parks the row again on its
first retryable failure:

```sql
UPDATE sc_pay_recurring_mandates.mandate_outbox_event
SET parked_at = NULL, first_failed_at = NULL, attempts = 0, last_error = NULL
WHERE event_id = '<event id>';
```

The replayed row goes out in `created_seq` order on the next run. A parked event can
put a mandate's later events ahead of it; consumers order by `aggregateVersion` in the
envelope and de-duplicate on `eventId`.
