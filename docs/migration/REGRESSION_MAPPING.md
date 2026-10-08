# Regression mapping: monolith recurring payments -> svc-pay-recurring-mandates

Status: **Proposed** (2026-10-08). Source: `enterprise-loan-management-system`,
`open-finance-context`, package `com.enterprise.openfinance.recurringpayments`
(`RecurringPaymentController`, `RecurringPaymentService`,
`RecurringPaymentExceptionHandler`). Target: this repository.

The paths are unchanged, so cutover is a routing change at the ingress gateway
(`/open-finance/v1/vrp/**`). See the
[runbook](RUNBOOK-EXTRACT-pay-recurring-mandates.md).

## Endpoints

| Monolith (method, path, handler) | New service | Field changes | Status code changes |
|---|---|---|---|
| `POST /open-finance/v1/vrp/payment-consents` (`createConsent`) | same path, `RecurringPaymentController.createConsent` | optional `Data.DebtorAccount.Identification` added; `Limit.Currency` upper-cased | 401 without a valid DPoP-bound token and proof; 400 if `ExpiryDateTime` is not in the future, if the amount has more decimals than the currency allows or the currency is unknown; 400 if the debtor account is not active, not debitable or in another currency; 503 if the accounts API is unreachable |
| `GET /open-finance/v1/vrp/payment-consents/{consentId}` (`getConsent`) | same | none | 401 without a valid token; 403 for another TPP (unchanged); `If-None-Match` 304 is computed from current state, so a revoked mandate no longer returns a stale 304 |
| `DELETE /open-finance/v1/vrp/payment-consents/{consentId}?reason=` (`revokeConsent`) | same | none | 401 without a valid token; 409 `CONCURRENT_UPDATE` if a concurrent change wins; second revoke stays 204 and raises no event |
| `POST /open-finance/v1/vrp/payments` (`submitPayment`) | same | none | 401 without a valid token; 400 for extra decimals or a debtor account that cannot be debited; 503 if the accounts API is unreachable; 409 `CONCURRENT_UPDATE` |
| `GET /open-finance/v1/vrp/payments/{paymentId}` (`getPayment`) | same | none | as `GET` consent |
| any other path | not served | - | 401 anonymous, 403 authenticated (`denyAll`) |

Missing required headers or a malformed body were 500 `INTERNAL_ERROR` in the
monolith (caught by its `Exception` handler); they are now 400 `INVALID_REQUEST`.

## Intentional behaviour changes

| Area | Monolith | New service |
|---|---|---|
| TPP identity | `x-fapi-financial-id` header, `UNKNOWN_TPP` when absent; any `Bearer`/`DPoP` string accepted | Validated JWT (issuer, signature, `aud` must contain `svc-pay-recurring-mandates`); TPP = `azp` (else `client_id`); a different `x-fapi-financial-id` is 403 |
| DPoP | header required, not validated; `Bearer` scheme accepted | enforced (RFC 9449): `DPoP` scheme, token bound by `cnf.jkt`, proof verified (signature with its jwk, `htm`, `htu`, `iat` within 5 min, `ath`) and its `jti` single-use across pods (`dpop_proof_jti`); a plain `Bearer` token or a missing, replayed or mismatching proof is 401 `WWW-Authenticate: DPoP error="invalid_dpop_proof"` |
| Persistence | in-memory only; lost on restart; per-pod state | PostgreSQL `sc_pay_recurring_mandates`; shared by all pods |
| Concurrency | JVM lock per pod | PostgreSQL advisory lock per mandate plus version compare-and-set |
| Idempotency | in-memory per pod | `mandate_idempotency_record`, unique per TPP and key, claimed atomically; TTL 24 h |
| Expiry at creation | not checked | must be in the future |
| Money | `BigDecimal` and a string, any scale | `Money`: ISO 4217, scale fixed to the currency's minor units, more decimals refused |
| Debtor account | not modelled | optional; checked through the accounts API at creation and before each collection; fails closed |
| Events | none | `evt.pay.mandate.created.v1`, `.revoked.v1`, `.payment-accepted.v1` through a transactional outbox |
| Response headers | `X-OF-Cache: HIT/MISS` | removed (cache is internal) |

## Not carried over

- Mandates and payments held in a monolith pod's memory at cutover; TPPs re-create mandates (no backfill, see runbook section 1).
- Monolith `integrationTest` / `functionalTest` source sets for this capability (they wire the in-memory adapters); their scenarios are covered by `RecurringMandatesServiceIT` and the controller tests.
