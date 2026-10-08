# Deployment and AWS Well-Architected mapping

How `svc-pay-recurring-mandates` runs on AWS, and which file implements each
Well-Architected concern. Status: **Proposed**; claims point at code, anything
not listed is not done.

## Runtime shape

```
TPP ─▶ Istio ingress ─▶ payment-recurring-mandates-service pods (EKS, ns payments, 3..12, HPA on CPU)
                           │  ├─ HTTP (service token) ─▶ accounts API (debtor account status)
                           │  └─ JDBC ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
                           └─ outbox relay ─▶ MSK (IAM) evt.pay.mandate.*.v1
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/payment-recurring-mandates-service` (`values.yaml` prod-shaped, `values-dev.yaml`) |
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA, MSK produce policy, alarms; platform `microservice-base` module) |
| Runtime config | `open-finance-bootstrap/src/main/resources/application.yml`, profiles `kafka-msk`, `kafka-strimzi` |
| CI proof | `.github/workflows/required-gates.yml`, `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Probes on the management port 8081; Prometheus metrics with the common tags `service` (service id), `app` (service account) and `squad` (namespace, which the platform outbox alerts route on), the last two overridable with `METRICS_TAG_APP` / `METRICS_TAG_SQUAD` and set by the chart; the API pods (not the migration Job pod) carry `fintechbankx.io/service-id: svc-pay-recurring-mandates`, the label the platform PodMonitor maps to `service_id` and the service-health alerts key on; outbox gauges `outbox_oldest_pending_age_seconds`, `outbox_pending_events`, `outbox_parked_rows` and counters `outbox_send_failures_total{exception}`, `outbox_parked_events_total{exception}` (platform Kafka guide 5f7d546; exception class only, no identifiers); OTLP traces to the platform collector and `traceparent` on every Kafka record; interaction id in logs, events and calls to the accounts API; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `OutboxRelay`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server, `aud` must contain the service id; TPP identity from the token (`azp`), a mismatching `x-fapi-financial-id` is 403; outbound calls with the service's own client-credentials token; non-root, read-only root FS, all capabilities dropped; DB and client secrets via External Secrets from KMS-encrypted Secrets Manager (key tagged `fintechbankx.io/secrets=true`), pods hold no secret-read rights; TLS enforced on Aurora (`rds.force_ssl`) and verified by the pods (`sslmode=verify-full` against the platform RDS CA bundle, ConfigMap `rds-ca-bundle` mounted read-only at `/etc/ssl/rds`); NetworkPolicy (8080 from the ingress namespace, 8081 from observability); events carry ids and facts only (the debtor account id is not published) | `SecurityConfiguration`, `TppIdentity`, `AccountsClientConfiguration`, `deployment.yaml`, `externalsecret.yaml`, `networkpolicy.yaml`, `main.tf`, `MandateEventEnvelopeFactory` |
| Reliability | Aurora Multi-AZ, 35-day PITR in prod, deletion protection; zone spread, PDB, zero-unavailable rollouts, graceful shutdown; transactional outbox, single ordered relay, idempotent producer, ADR-021 decision 4: payload errors park the row at once and hold back that mandate's later events; every other failure stops the batch without marking a row and backs off (2 s to 5 min), with no time-based parking; operators park by hand with a recorded reason; idempotent collections (unique key per TPP); per-mandate advisory lock plus version compare-and-set keep the monthly limit under concurrency; accounts check fails closed with 1 s / 2 s timeouts | `main.tf`, `deployment.yaml`, `pdb.yaml`, `OutboxRelay`, `JpaVrpIdempotencyAdapter`, `PostgresAdvisoryVrpLockAdapter`, `JpaVrpConsentAdapter` |
| Performance efficiency | Stateless pods scaled on CPU; Aurora Serverless v2; virtual threads; partial index for the monthly sum and the outbox queue; lz4 and 5 ms linger on the producer | `hpa.yaml`, `V1__create_mandate_tables.sql`, `V2__create_outbox.sql`, `application.yml` |
| Cost optimization | Serverless v2 floor 0.5 ACU in dev; dev overrides (one Aurora instance, 2-4 pods); outbox and idempotency rows purged; 30-day logs outside prod | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished`, `IdempotencyRecordPurge` |
| Sustainability | Scale-down policy and Serverless ACUs follow load; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Known gaps

- No repository serves the accounts API to services yet, and the monolith has no internal account-status read (its only account read is the TPP-facing AIS endpoint, which needs a PSU AIS consent and a DPoP-bound TPP token). `ACCOUNTS_SERVICE_BASE_URL` (required by the chart) and `ACCOUNTS_SERVICE_PATH` (default `/api/v1/accounts/{accountId}`) must point at the system of record once one exists; until then a mandate whose consent names a debtor account fails closed with 503. This is an interim gap, not a design.
- DPoP is enforced on the TPP-facing VRP API (platform contract: DPoP applies by caller): DPoP scheme, `cnf.jkt`-bound token, proof signature/htm/htu/iat/ath checks and a single-use jti in PostgreSQL (`dpop_proof_jti`, purged every 10 minutes). Plain Bearer is 401. Internal callers, if any appear, get `/api/v1` paths with Bearer.
- The read cache (`InMemoryVrpCacheAdapter`) is per pod: a GET on another replica may show a mandate as Authorised for up to 30 s after revocation. Collections always read the database.
- Kafka topics and ACLs for `evt.pay.mandate.*.v1` are not yet in the platform topic catalog; the relay is off until they are.
- `msk-client-access` is not on the terraform-modules main branch; an inline topic-scoped IAM policy is used (TODO in `main.tf`).
- `microservice-base` is referenced at `ref=main`; pin a tag once released.
- No load test yet; HPA targets are starting values.
