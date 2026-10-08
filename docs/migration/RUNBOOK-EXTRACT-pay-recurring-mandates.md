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
| Depends on | Keycloak realm `fintechbankx` (TPP tokens with `aud` = service id and DPoP binding `cnf.jkt`, so TPP clients must be DPoP-enabled before cutover; client-credentials client `svc-pay-recurring-mandates`); consent-authorization-service `GET /api/v1/consents/{id}` (`CONSENT_SERVICE_BASE_URL`; this service must be on its allow-list, which the provider branch already has) for the PSU-authorised consent every mandate is bound to; accounts API `GET /api/v1/accounts/{accountId}` at `ACCOUNTS_SERVICE_BASE_URL` for the optional debtor account |

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
  `V2__create_outbox.sql`. The service never reads monolith tables; nothing
  else may read `sc_pay_recurring_mandates`.

## 2. Cutover plan (routing only)

| Step | Action | Rollback |
|---|---|---|
| 1 | DBA bootstrap: create role `pay_recurring_mandates_app` owning schema `sc_pay_recurring_mandates`, write its credential to the Terraform secret `<env>-payment-recurring-mandates-service/db-app`. Deploy with `OUTBOX_RELAY_ENABLED=false`. | uninstall the chart; drop the schema |
| 2 | Mesh repository adds the ALLOW rule for the ingress gateway principal `cluster.local/ns/istio-ingress/sa/istio-ingressgateway` on `payment-recurring-mandates-service` (no internal callers today). | remove the rule |
| 3 | Route `/open-finance/v1/vrp/**` at the ingress gateway from the monolith to this service; announce to TPPs that mandates must be re-created. | route back to the monolith (its in-memory state was empty after any restart, so nothing is lost either way) |
| 4 | Once `evt.pay.mandate.*.v1` exist in the platform topic catalog: `OUTBOX_RELAY_ENABLED=true`. Events written since step 1 are relayed in order. | relay off; events stay in the outbox |
| 5 | Remove `recurringpayments` from the monolith (`open-finance-context`). | revert the removal commit |

Rollback triggers (any one, measured over 15 minutes after a step): 5xx rate on
`/open-finance/v1/vrp/**` above 1 %; p99 latency above 1 s; `outbox_parked_events`
above 0; 401 rate with `invalid_dpop_proof` above 5 % of VRP calls (TPPs not DPoP-ready); `outbox_oldest_pending_age_seconds` above 300 with the relay enabled.

## 3. Acceptance checklist

- [x] Service builds and tests standalone (`./gradlew check`, including PostgreSQL integration tests with `TEST_DB_URL`)
- [x] Own schema and migrations; Hibernate validates entities at startup
- [x] Events written through a transactional outbox, relayed in order with one active relay; poison events parked
- [x] Idempotent collections (`x-idempotency-key`, unique per TPP in the database, race-tested)
- [x] Monthly limit enforced under concurrency (advisory lock per mandate plus version compare-and-set)
- [x] Debtor account check through the accounts API with a service token, failing closed
- [ ] An accounts API serving `GET /api/v1/accounts/{accountId}` (no fintechbankx repository owns accounts yet)
- [ ] Topics `evt.pay.mandate.*.v1` in the platform topic catalog and AsyncAPI catalog PR merged
- [ ] Mesh ALLOW rule for the ingress gateway (mesh repository)
- [ ] Ingress route switched; monolith `recurringpayments` removed
