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
| Operational excellence | Probes on the management port 8081; Prometheus metrics tagged `service`; outbox gauges `outbox_pending_events`, `outbox_parked_events`, `outbox_oldest_pending_age_seconds`; OTLP traces to the platform collector and `traceparent` on every Kafka record; interaction id in logs, events and calls to the accounts API; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `OutboxRelay`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server, `aud` must contain the service id; TPP identity from the token (`azp`), a mismatching `x-fapi-financial-id` is 403; outbound calls with the service's own client-credentials token; non-root, read-only root FS, all capabilities dropped; DB and client secrets via External Secrets from KMS-encrypted Secrets Manager (key tagged `fintechbankx.io/secrets=true`), pods hold no secret-read rights; TLS enforced on Aurora; NetworkPolicy (8080 from the ingress namespace, 8081 from observability); events carry ids and facts only (the debtor account id is not published) | `SecurityConfiguration`, `TppIdentity`, `AccountsClientConfiguration`, `deployment.yaml`, `externalsecret.yaml`, `networkpolicy.yaml`, `main.tf`, `MandateEventEnvelopeFactory` |
| Reliability | Aurora Multi-AZ, 35-day PITR in prod, deletion protection; zone spread, PDB, zero-unavailable rollouts, graceful shutdown; transactional outbox, single ordered relay, idempotent producer, poison events parked after `max-attempts`; idempotent collections (unique key per TPP); per-mandate advisory lock plus version compare-and-set keep the monthly limit under concurrency; accounts check fails closed with 1 s / 2 s timeouts | `main.tf`, `deployment.yaml`, `pdb.yaml`, `OutboxRelay`, `JpaVrpIdempotencyAdapter`, `PostgresAdvisoryVrpLockAdapter`, `JpaVrpConsentAdapter` |
| Performance efficiency | Stateless pods scaled on CPU; Aurora Serverless v2; virtual threads; partial index for the monthly sum and the outbox queue; lz4 and 5 ms linger on the producer | `hpa.yaml`, `V1__create_mandate_tables.sql`, `V2__create_outbox.sql`, `application.yml` |
| Cost optimization | Serverless v2 floor 0.5 ACU in dev; dev overrides (one Aurora instance, 2-4 pods); outbox and idempotency rows purged; 30-day logs outside prod | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished`, `IdempotencyRecordPurge` |
| Sustainability | Scale-down policy and Serverless ACUs follow load; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Known gaps

- No repository serves the accounts API yet; `ACCOUNTS_SERVICE_BASE_URL` is required by the chart and must point at the system of record. Mandates without a debtor account skip the check.
- DPoP proofs are required as a header by the FAPI contract but not validated (platform decision 2026-10-08 for lending and payments). VRP is TPP-facing; revisit with the open-finance squad.
- The read cache (`InMemoryVrpCacheAdapter`) is per pod: a GET on another replica may show a mandate as Authorised for up to 30 s after revocation. Collections always read the database.
- Kafka topics and ACLs for `evt.pay.mandate.*.v1` are not yet in the platform topic catalog; the relay is off until they are.
- `msk-client-access` is not on the terraform-modules main branch; an inline topic-scoped IAM policy is used (TODO in `main.tf`).
- `microservice-base` is referenced at `ref=main`; pin a tag once released.
- No load test yet; HPA targets are starting values.
