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
  `V2__create_outbox.sql`, `V3__create_dpop_proof_jti.sql`, `V4__outbox_first_failed_at.sql`, `V5__grant_runtime_role_least_privilege.sql`, `V6__outbox_park_reason_no_time_ceiling.sql`, `V7__outbox_park_counted.sql`. The service never reads monolith tables; nothing
  else may read `sc_pay_recurring_mandates`.

## 2. Cutover plan (routing only)

| Step | Action | Rollback |
|---|---|---|
| 1 | DBA bootstrap (section "Database roles" below): create the schema owner `pay_recurring_mandates_owner` and the runtime role `pay_recurring_mandates_app`, write their credentials to `<env>/payment-recurring-mandates-service/db-migration` and `<env>/payment-recurring-mandates-service/db-app` (ESO may read only `<env>/<service account>/`). Set `config.DB_URL` to the Terraform output `jdbc_url` (`sslmode=verify-full&sslrootcert=/etc/ssl/rds/global-bundle.pem`; the chart refuses anything else and mounts ConfigMap `rds-ca-bundle`, which trust-manager must have published in `payments`), `migration.remoteSecretName` to `migration_db_secret_name`. Deploy with `OUTBOX_RELAY_ENABLED=false`; the pre-install hook Job runs Flyway as the owner (V1 to V7) before the pods start. | uninstall the chart; drop the schema |
| 2 | Mesh team has applied "Requests to the mesh team" below: B (Aurora, MSK, STS egress) before step 1 completes, otherwise the readiness check (`db`) fails under `REGISTRY_ONLY`; A (gateway route) and C (callee ALLOW rules) before step 3. | remove the route; ALLOW rules and egress can stay |
| 3 | Route `/open-finance/v1/vrp/**` at the ingress gateway from the monolith to this service; announce to TPPs that mandates must be re-created. The soak window starts (72 h without a rollback trigger). | "Rollback during the soak window" below: freeze the writes, route back, account for what stays here |
| 4 | Only after the soak window ended without a rollback, and once `evt.pay.mandate.*.v1` exist in the platform topic catalog: `OUTBOX_RELAY_ENABLED=true`. Events written since step 3 are relayed in order. | relay off; unsent events stay in the outbox (events already published cannot be recalled) |
| 5 | Remove `recurringpayments` from the monolith (`open-finance-context`). | revert the removal commit |

Rollback triggers (any one, measured over 15 minutes after a step): 5xx rate on
`/open-finance/v1/vrp/**` above 1 %; p99 latency above 1 s; any increase of
`outbox_parked_events_total`; 401 rate with `invalid_dpop_proof` above 5 % of VRP calls (TPPs not DPoP-ready); `outbox_oldest_pending_age_seconds` above 300 with the relay enabled.

### Rollback during the soak window

The outbox relay stays off (`OUTBOX_RELAY_ENABLED=false`) for the whole soak window, so no
consumer learns of a mandate that a rollback would orphan.

1. Freeze writes: at the ingress gateway, answer `POST /open-finance/v1/vrp/payment-consents`
   and `POST /open-finance/v1/vrp/payments` with 503 (`Retry-After`) for both backends. Reads
   and `DELETE` (revocation) stay open on this service until step 3 of this list.
2. Export the state created since cutover, as the schema owner:
   `mandate_record` (mandates, including revocations), `mandate_payment` (accepted
   collections and the month they count against) and the unsent `mandate_outbox_event` rows.
3. Route `/open-finance/v1/vrp/**` back to the monolith, then lift the freeze.

What a rollback loses or leaves to replay, exactly:

- **Lost on the monolith side**: every mandate created and every collection accepted on this
  service since cutover. The monolith keeps mandates in memory only and cannot import them; TPPs
  must re-create those mandates on the monolith, and their PSUs must authorise again.
- **Monthly limit not carried over**: collections accepted here do not count toward the
  monolith's limit. Until the month ends, a re-created mandate could collect up to its full limit
  again on the monolith; the payments squad uses the export from step 2 to cap or refuse those
  collections by hand.
- **Revocations made here** are not known to the monolith; mandates revoked here must not be
  re-created there (check the export).
- **Nothing published to be recalled**: with the relay off, no `evt.pay.mandate.*` event left this
  service; the unsent outbox rows stay in `sc_pay_recurring_mandates` and are discarded (or relayed
  later if the cutover is retried with the same data, which also needs the mandates restored).
- **Not lost**: the data in `sc_pay_recurring_mandates` itself; the schema is kept until the
  cutover is retried or formally abandoned.
- Mandates that lived in the monolith's memory before cutover were already gone (restart or
  cutover); a rollback does not bring them back.

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

### Requests to the mesh team

Raise these in `fintechbankx-platform-mesh-security-service-mesh` (owner: platform mesh
squad) before step 1. The chart ships no Istio objects; everything below is theirs to add.
Namespace `payments` is default-deny and outbound traffic is `REGISTRY_ONLY`.

**A. Gateway route (public paths).** Host: the Open Finance API host of the environment.
Route to `payment-recurring-mandates-service.payments.svc.cluster.local:8080`:
`POST /open-finance/v1/vrp/payment-consents`, `GET` and `DELETE
/open-finance/v1/vrp/payment-consents/*`, `POST /open-finance/v1/vrp/payments`, `GET
/open-finance/v1/vrp/payments/*`, with the platform's forwarded-header rules (the DPoP
`htu` check uses `X-Forwarded-Proto/Host/Port`) and Keycloak `RequestAuthentication` for
TPP tokens (`aud` must contain `svc-pay-recurring-mandates`, DPoP-bound). Nothing else is
public; `8081` (actuator) never is. Inbound ALLOW: principal
`cluster.local/ns/istio-ingress/sa/istio-ingressgateway` on port 8080.

**B. Egress under `REGISTRY_ONLY` (ServiceEntry, `MESH_EXTERNAL`, `resolution: DNS`,
exported to `payments`).**

| Destination | Hosts | Port / protocol | Why |
|---|---|---|---|
| Aurora PostgreSQL | cluster writer endpoint (Terraform output `jdbc_url` host) and `reader_endpoint` | 5432 `TLS` (the service does its own TLS, `sslmode=verify-full`) | JDBC; the readiness group includes `db`, so without it the pods never become ready |
| Amazon MSK | broker hostnames of the IAM listener (`KAFKA_BOOTSTRAP_SERVERS`) | 9098 `TLS` | outbox relay (IAM auth via IRSA); needed before `OUTBOX_RELAY_ENABLED=true` |
| AWS STS (regional endpoint) | `sts.<region>.amazonaws.com` | 443 `TLS` | IRSA web-identity exchange used by the MSK IAM client |

The Flyway migration Job (Helm hook) runs without a sidecar by default
(`migration.istioSidecar: false`), so it is not subject to the egress policy and only
reaches Aurora; tell the mesh team if the cluster runs native sidecars, then turn the
sidecar on.

**C. Callee ALLOW rules (this service as the caller, principal
`cluster.local/ns/payments/sa/payment-recurring-mandates-service`).**

| Callee namespace / workload | Port | Paths | Why | Status |
|---|---|---|---|---|
| `open-finance` / `consent-authorization-service` | 8080 | `GET /api/v1/consents/*` | PSU-authorised consent behind every mandate and collection | applied by the mesh team per coordinator note 2026-10-08 (not verified from this repository) |
| accounts API (no owner yet; interim, see checklist) | as configured | `GET` on `ACCOUNTS_SERVICE_PATH` | debtor account status | to request once a provider exists |
| `identity` / `keycloak` | 8080 | `POST /realms/fintechbankx/protocol/openid-connect/token` and the realm `certs` (JWKS) | client-credentials token and JWT validation | to request |
| `observability` / `otel-collector` | 4318 | OTLP HTTP | traces | to request |

## 3. Acceptance checklist

- [x] Service builds and tests standalone (`./gradlew check`, including PostgreSQL integration tests with `TEST_DB_URL`)
- [x] Own schema and migrations; Hibernate validates entities at startup
- [x] Flyway as the schema owner in a Helm hook Job; pods run as a DML-only role (IT proves the runtime role cannot run DDL)
- [x] Events written through a transactional outbox, relayed in order with one active relay; payload errors parked at once and their mandate held back; every other failure stops the batch without marking a row and backs off (ADR-021 decision 4, no time-based parking)
- [x] Idempotent collections (`x-idempotency-key`, unique per TPP in the database, race-tested)
- [x] Monthly limit enforced under concurrency (advisory lock per mandate plus version compare-and-set)
- [x] Debtor account check through the accounts API with a service token, failing closed
- [ ] An accounts API serving `GET /api/v1/accounts/{accountId}` to services (interim gap: the monolith has no internal account-status read; its only account read is the TPP-facing AIS `GET /open-finance/v1/accounts/{accountId}` in `open-finance-context`, which needs a PSU AIS consent, a DPoP-bound TPP token and returns `Data.Account.Status` without a debit flag; it is being extracted to svc-of-personal-financial-data. `ACCOUNTS_SERVICE_BASE_URL` and `ACCOUNTS_SERVICE_PATH` are configurable; until a provider exists, mandates naming a debtor account fail closed with 503)
- [ ] Topics `evt.pay.mandate.*.v1` in the platform topic catalog and AsyncAPI catalog PR merged
- [ ] Mesh team requests A (gateway route and ingress ALLOW), B (Aurora, MSK, STS egress) and C (callee ALLOW rules) applied; consent-authorization-service ALLOW reported applied, others open
- [ ] Ingress route switched; monolith `recurringpayments` removed

## 4. Parked outbox events

Policy: ADR-021 decision 4 (adr-runbooks #10, e6dd76a). There is no attempt cap and no
time-based parking. A failed send falls into one of two classes:

| Class | Errors | What the relay does | Signal |
|---|---|---|---|
| Payload | `RecordTooLargeException`, `SerializationException`, `InvalidTopicException` | parks the row at once (`parked_reason` = `relay: payload error ...`) and continues with other mandates; the mandate's later events stay held back | `outbox_parked_events_total{exception="<class>"}` increases (alert on any increase); gauge `outbox_parked_rows` shows rows parked now |
| Everything else | retriable broker or network errors (`TimeoutException`, `NotEnoughReplicasException`, ...), the relay's own send timeout, `SaslAuthenticationException`, `TopicAuthorizationException`, producer construction failures, anything unclassified | never parks: stops the batch without marking any row (no park, no attempt, no `last_error`) and retries with backoff 2 s doubling to 5 min, reset by the next successful send | `outbox_oldest_pending_age_seconds` grows; `outbox_send_failures_total{exception="<class>"}` counts each failure (tag = exception class only, never ids) |

A parked row holds back its mandate: the relay publishes none of that mandate's later
events, in that run or later ones, until the parked row is replayed or discarded. Other
mandates keep flowing, so consumers never see a mandate's events out of order.

An increase of `outbox_parked_events_total` (or `outbox_parked_rows` above 0) means a
consumer is missing an event. Find the rows:

```sql
SELECT event_id, created_seq, topic, aggregate_id, attempts, parked_reason, last_error, parked_at
FROM sc_pay_recurring_mandates.mandate_outbox_event
WHERE parked_at IS NOT NULL
ORDER BY created_seq;
```

Replay after fixing the cause (topic ACL, topic missing, payload size), as the schema owner:

```sql
UPDATE sc_pay_recurring_mandates.mandate_outbox_event
SET parked_at = NULL, parked_reason = NULL, park_counted = false, attempts = 0, last_error = NULL
WHERE event_id = '<event id>';
```

The replayed row goes out on the next run, followed by the mandate's held-back events in
`created_seq` order. To discard a parked event instead (only with the consumers' owners'
agreement, since they then never see it), delete the row as the schema owner; the
mandate's later events then flow. Consumers de-duplicate on `eventId`.

**Operator park** (the only way a row that is not a payload error gets parked, e.g. a
stuck head row blocking the queue while a fix is prepared). The reason is mandatory; the
V6 check constraint `ck_outbox_parked_reason` refuses a park without one:

```sql
UPDATE sc_pay_recurring_mandates.mandate_outbox_event
SET parked_at = now(), parked_reason = 'operator: <ticket> <why>'
WHERE event_id = '<event id>';
```

Its mandate's later events are then held back too; replay as above. The relay counts the
operator park once on its next run (`outbox_parked_events_total{exception="OperatorPark"}`,
column `park_counted`, V7); a replay should also reset `park_counted = false` so a later
park is counted again.
