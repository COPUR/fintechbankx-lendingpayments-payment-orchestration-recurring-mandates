# fintechbankx-lendingpayments-payment-orchestration-recurring-mandates

Bu repository, FinTechBankX DDD/EDA dönüşümünde **svc-pay-recurring-mandates** servis yetkinliğinin kaynak kodunu, kontratlarını ve operasyonel guardrail'lerini içerir.

## Service at a glance (2026-10-08, Proposed)

| Item | Value |
|---|---|
| Service id / Keycloak client | `svc-pay-recurring-mandates` |
| Service name / Helm, image, SA | `fintechbankx-payments-recurring-mandates-service` / `payment-recurring-mandates-service` (namespace `payments`) |
| `spring.application.name` / app label | `app.pay.recurring-mandates` / `fintechbankx.io/app: app-pay-recurring-mandates` |
| Package root | `com.enterprise.openfinance.recurringpayments` (`domain`, `domain.port.in`, `domain.port.out`, `application`, `infrastructure.<tech>`) |
| Modules | `open-finance-domain`, `open-finance-application`, `open-finance-infrastructure`, `open-finance-bootstrap` (Spring Boot app) |
| Data | `db_pay_recurring_mandates_<env>`, schema `sc_pay_recurring_mandates`: `mandate_record`, `mandate_payment`, `mandate_idempotency_record`, `mandate_outbox_event`, `dpop_proof_jti` (Flyway V1 to V8). Two roles: schema owner (Flyway, Helm hook Job, secret `db-migration`) and a DML-only runtime role for the pods (secret `db-app`) |
| API | [OpenAPI](api/openapi/recurring-mandates-service.yaml), base `/open-finance/v1/vrp` |
| Events | [AsyncAPI](api/asyncapi/svc-pay-recurring-mandates.yaml) |
| Ports | 8080 `http`, 8081 `http-management` (actuator, Prometheus) |
| TPP security | Keycloak JWT with `aud` = `svc-pay-recurring-mandates`; DPoP (RFC 9449) enforced on `/open-finance/v1/vrp/**`: DPoP scheme, `cnf.jkt`-bound token, fresh proof per request (jti replay table); plain Bearer is 401. `DPOP_REQUIRED` (default `true`) |

### Ownership tags

| Tag | Value |
|---|---|
| bounded_context | `payment_recurring_mandates` |
| owning_squad | Recurring and Bulk Payments Squad |
| owning_tribe | Lending & Payments Tribe |
| review_cadence | quarterly |
| data_owner | svc-pay-recurring-mandates (`sc_pay_recurring_mandates`) |
| upstream_dependencies | Keycloak realm `fintechbankx` (TPP tokens, client credentials); consent-authorization-service `GET /api/v1/consents/{id}` at `CONSENT_SERVICE_BASE_URL` (PSU-authorised consents, scope `INITIATEVRP`); accounts API `GET /api/v1/accounts/{accountId}` at `ACCOUNTS_SERVICE_BASE_URL` + `ACCOUNTS_SERVICE_PATH` (interim: no provider serves it to services yet; calls fail closed with 503) |
| published_events | `evt.pay.mandate.v1`, one topic per aggregate (ADR-019): `Payments.Mandate.Created.v1`, `Payments.Mandate.Revoked.v1`, `Payments.Mandate.PaymentAccepted.v1`, keyed by the mandate id, event named by the `eventType` record header; no DLQ: dead-letter topics are owned by the consuming service (ADR-019/024) |
| consumed_events | none |

### Run, test, deploy

| Task | Command / place |
|---|---|
| Full gate (unit, ArchUnit, Jacoco 85 % line, integration) | `./gradlew --no-daemon clean check` |
| Integration tests against PostgreSQL | set `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`; without them they skip locally and fail when `CI=true` |
| Run locally without Kafka | `SPRING_PROFILES_ACTIVE=local DB_URL=jdbc:postgresql://localhost:5432/<db> DB_USERNAME=<user> SPRING_DATASOURCE_PASSWORD=<password> ACCOUNTS_ADAPTER=in-memory CONSENT_ADAPTER=in-memory OUTBOX_RELAY_ENABLED=false java -jar open-finance-bootstrap/build/libs/payment-recurring-mandates-service.jar` (single-user: Flyway runs in-process as that user and V5 only logs that privileges are not separated; the `local` profile turns the startup TLS assertion off for the plain localhost database) |
| Startup TLS assertion | `fintechbankx.tls.enforce` (default `true`, `application.yml`): the service, the migration Job and every test context fail fast unless `spring.datasource.url` carries `sslmode=verify-full` and, when a Kafka client is configured, its effective `security.protocol` is `SASL_SSL` (`TlsEnforcementInitializer`, registered in `spring.factories`; the message names the setting and the value found, never the URL). Only the `local` profile and the bootstrap test resources set it `false`; the chart refuses both the key and the profile |
| Event contract gate (ADR-019 section 5) | `npm install --no-save yaml@2.9.1 && ASYNCAPI_DIR=api/asyncapi BASE_REF=origin/main ./scripts/ci/asyncapi-breaking.sh`, plus `npx -y @asyncapi/cli@2.13.0 validate api/asyncapi/svc-pay-recurring-mandates.yaml`; both run in `ci/test`. `scripts/ci/asyncapi-breaking.mjs` (sha256 `de255737fe6b54ffbadce8e030e18eec48d0137e0abd43c790fe201a10015eb1`), `scripts/ci/asyncapi-breaking.sh` (sha256 `5b39d588673c5f7ab55fcfa548fffd70b96ab9b11ea82bd2b61468dadb430c77`) and `scripts/ci/lib/asyncapi-model.mjs` (sha256 `212df6ca092e1ed5519664d7848c9de5fdb5d137f34faa3d31a25e3fe29b3847`) are copies of the AsyncAPI catalog's files at commit 44837cc, as is `api/asyncapi/common/event-envelope.yaml` (sha256 `299e8731ccd365d8b1bccb698c3ae0e48812e1d2da85ed435680fcc7c61867ed`), the catalog's `asyncapi/common/event-envelope.yaml` (verbatim; its `DeadLetterHeaders` is unreferenced here because this service consumes nothing and owns no DLQ, ADR-019 section 1); `ci/test` fails when `sha256sum` of a copy differs (step "AsyncAPI gate scripts match the catalog copy"). Do not edit them: copy again and update the commit and the sums here and in the workflow. The spec is pre-release (not on `main` yet), stays at 1.0.0 and has no waiver |
| Container | [Dockerfile](Dockerfile) |
| Kubernetes | [Helm chart](deploy/helm/payment-recurring-mandates-service). Values cannot redirect Spring configuration: a `config` key or `extraEnv` name spelling `SPRING_CONFIG_IMPORT`, `SPRING_CONFIG_LOCATION` or `SPRING_CONFIG_ADDITIONAL_LOCATION` (any case), `SPRING_DATASOURCE_URL`, `SPRING_FLYWAY_URL` or `FINTECHBANKX_TLS_ENFORCE` fails the render (`templates/_helpers.tpl`, `mandates.guardEnvKey`; CI step "Spring config redirection fails the render"). A configtree is allowed only when the chart itself renders it on `optional:configtree:/etc/fintechbankx/config/`; this chart renders none |
| AWS | [Terraform](deploy/terraform) |
| Cutover | [Runbook](docs/migration/RUNBOOK-EXTRACT-pay-recurring-mandates.md), [regression mapping](docs/migration/REGRESSION_MAPPING.md) |
| Architecture | [Deployment and Well-Architected notes](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |

Deployed, the pods verify Aurora's certificate: `DB_URL` must carry
`sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem` (Terraform output
`jdbc_url`), and the chart mounts the platform ConfigMap `rds-ca-bundle` there. Local
runs and tests keep their own URLs.

The outbox relay is off by default (`OUTBOX_RELAY_ENABLED=false`) until the
topic `evt.pay.mandate.v1` exists in the platform topic catalog; the AsyncAPI catalog PR is pending.

### Service mesh

The chart has no PeerAuthentication or AuthorizationPolicy; the mesh
repository owns them. Callers that need an ALLOW rule on
`payment-recurring-mandates-service`: the ingress gateway principal
`cluster.local/ns/istio-ingress/sa/istio-ingressgateway`. There are no internal
callers today. Outbound: consent-authorization-service (ALLOW rule for
`cluster.local/ns/payments/sa/payment-recurring-mandates-service` on `GET /api/v1/consents/*`,
reported applied by the mesh team), the accounts API and Keycloak. The full list of
requests to the mesh team (gateway route, Aurora/MSK/STS egress under `REGISTRY_ONLY`,
callee ALLOW rules) is in the runbook, "Requests to the mesh team".

### Mandates are bound to PSU-authorised consents

`POST /payment-consents` takes `Data.ConsentId`, a consent the PSU authorised in
consent-authorization-service (`usable` = true, participant = the calling TPP, scope
`INITIATEVRP`). The mandate takes that consent's id, PSU and debtor account; the TPP
supplies only the monthly limit. A stated `PsuId` or later `ExpiryDateTime` that
differs from the consent is 403; a `DebtorAccount` outside the consent, unknown to the
accounts API or not usable (inactive, no debits, other currency) is always the same 400
`DebtorAccount cannot be used for this mandate`, so the API does not reveal whether an
account exists or whose it is; a `ConsentId` in a request body that the caller may not
use (unknown, not authorised, expired, another TPP's, no `INITIATEVRP`) is always the same
403 `Consent not found or not authorised`; a second mandate for one consent is 409;
consent service down is 503. On path ids (`GET`/`DELETE /payment-consents/{id}`,
`GET /payments/{id}`) an unknown id and another TPP's get the same 404 (`Consent not
found`, `Payment not found`; ADR-025 item 5). The reason of each refusal is logged with the
interaction id, never returned. Every collection re-reads the consent, so a PSU who
withdraws it stops further collections.
All remote calls run before the database transaction and the mandate lock.

### Limit rule and its scope

A mandate has one cumulative monthly limit, as in the monolith: the accepted total
per calendar month may not exceed `Limit.Amount`. The month is the calendar month in
`mandates.limit-period-zone` (env `MANDATES_LIMIT_PERIOD_ZONE`, default `Asia/Dubai`),
so a collection at 20:30Z on 28 February counts against March. Changing the zone
moves period boundaries for existing mandates; agree it with the squad first.

Follow-up, not in scope (the monolith has none of these): the UAE VRP control
parameters also define a per-payment maximum, per-period limits for Day, Week,
Fortnight, Month, HalfYear and Year, a total over the consent lifetime and payment
count limits. Adding them needs the consent view to expose the PSU-authorised
control parameters (today the limit is asserted by the TPP).

### Removed from this repository and who owns it

The seed copied generic open-finance code that was never compiled here
(commit "remove seeded open-finance residue"):

| Removed | Owner |
|---|---|
| Consent sagas, the Consent/Participant model and events, `DistributedConsentService`, Redis consent cache, consent controller | `fintechbankx-openfinance-consent-auth-service` |
| Loan and account controllers | `fintechbankx-lendingpayments-loan-lifecycle-core`; accounts: no fintechbankx owner yet |
| CBUAE directory client, Mongo analytics, monitoring models, event store | not this capability; the monolith keeps them until their owner is decided (no fintechbankx repository owns them today) |
| `infra/terraform` (referenced a module that does not exist here) | replaced by `deploy/terraform` |

## Sorumluluk ve Sahiplik
| Alan | Değer |
|---|---|
| Organizasyon Modeli | Spotify Model (Tribe/Squad) |
| Tribe | Lending & Payments Tribe |
| Squad | Recurring and Bulk Payments Squad |
| Repo Kümesi (Capability) | payments |
| Service ID | svc-pay-recurring-mandates |
| Bounded Context | payment_recurring_mandates |
| Wave | 3 |
| Mimari Yaklaşım | DDD + Hexagonal + Event-Driven |

## Sorumluluk Sınırları
- Bu repo kendi bounded context domain modelinin tek yetkili sahibidir.
- Domain kuralları altyapıdan bağımsız tutulur; entegrasyonlar port/adapter katmanında yönetilir.
- API/Event kontratları geriye dönük uyumluluk kontrolleri ile korunur.
- Güvenlik guardrail'leri (mTLS, token doğrulama, idempotency, log hijyeni) CI/CD ile zorlanır.

## Kapsam
### In Scope
- payment_recurring_mandates bağlamına ait uygulama kodu, testler ve otomasyon.
- Bu servise ait OpenAPI/AsyncAPI veya şema artefaktları.
- Bu servisin çalışma zamanı operasyonları (gözlemlenebilirlik, release, rollback).

### Out of Scope
- Diğer bounded context'lerin iş kuralları ve veri sahipliği.
- Paylaşımlı DB anti-pattern'i; cross-context doğrudan tablo erişimi.
- Platform dışı gizli bilgi/anahtar yönetimi (merkezi policy dışında local hardcode).

## Mühendislik Standartları
- **TDD öncelikli** geliştirme, birim test + entegrasyon testi.
- **Clean Architecture**: Domain katmanı framework bağımsız.
- **12-Factor** ve environment-driven configuration.
- **FAPI odaklı güvenlik** (OIDC/OAuth2, mTLS, DPoP gereksinimleri ilgili servislerde).
- **PII güvenliği**: loglarda maskeleme, secret'ların source/env içine yazılmaması.

## Branching ve Release Akışı
- Uzun ömürlü branch'ler: `main`, `dev`, `staging`, `local`.
- Feature branch kuralı: `codex/<kisa-aciklama>`.
- Release yaklaşımı: PR + required status checks + tag tabanlı sürümleme.

## Dokümantasyon ve Referanslar
- [Enterprise Architecture Hub](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture)
- [Secure Microservices Architecture](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/architecture/overview/SECURE_MICROSERVICES_ARCHITECTURE.md)
- [Service Data Ownership Matrix](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_DATA_OWNERSHIP_MATRIX.md)
- [Service API Contracts Index](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_API_CONTRACTS_INDEX.md)
- [Transformation Plan](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/MICROSERVICES_TRANSFORMATION_PLAN.md)
- [Capability Map (PUML)](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/puml/service-mesh/enterprise-capability-map.puml)
- [Bu Repo Dokümantasyonu](./docs)

## Güvenlik ve Uyumluluk Notları
- Gerçek secret değerleri repo veya `.env` içinde tutulmaz.
- Secret üretim/rotasyon olayları merkezi log/SIEM'e taşınır.
- CI pipeline, anonimlik ve local-path sızıntısı kontrollerini bloklayıcı olarak çalıştırır.

## Katkı
- Katkı süreci için `CONTRIBUTING.md` ve squad runbook'ları izlenmelidir.
- PR'larda mimari kararlar ADR veya backlog referansı ile ilişkilendirilmelidir.

<!-- cell-architecture-start -->
## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: docs/architecture/CELL_BASED_ARCHITECTURE_IMPLEMENTATION_PLAN.md
- Backlog: docs/project-management/CELL_ARCHITECTURE_BACKLOG_BOARD.md
<!-- cell-architecture-end -->
