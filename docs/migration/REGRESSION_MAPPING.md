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
| `POST /open-finance/v1/vrp/payment-consents` (`createConsent`) | same path, `RecurringPaymentController.createConsent` | **`Data.ConsentId` required** (PSU-authorised consent in consent-authorization-service; becomes the mandate id); `PsuId` and `ExpiryDateTime` now optional and must match the consent; optional `Data.DebtorAccount.Identification` (must be a consent account; required when the consent has several: without it, 400 `BUSINESS_RULE_VIOLATION` "DebtorAccount.Identification is required: the consent covers more than one account"); `Limit.Currency` upper-cased (status unchanged, 201 Created in both; only the returned value differs, e.g. `aed` -> `AED`) | 401 without a valid DPoP-bound token and proof; 403 when the consent is missing, not usable, of another TPP, without `INITIATEVRP`, or when PsuId or a later ExpiryDateTime differ from it; 409 `MANDATE_EXISTS` for a second mandate on one consent; 503 when the consent service is down; 400 if `ExpiryDateTime` is not in the future, if the amount has more decimals than the currency allows or the currency is unknown; the same 400 `BUSINESS_RULE_VIOLATION` "DebtorAccount cannot be used for this mandate" for a debtor account outside the consent, unknown to the accounts API, inactive, not debitable or in another currency (no account enumeration); 503 if the accounts API is unreachable |
| `GET /open-finance/v1/vrp/payment-consents/{consentId}` (`getConsent`) | same | none | 401 without a valid token; an unknown id stays 404 and another TPP's id is now the same 404 `NOT_FOUND` "Consent not found" (was 403 "Consent participant mismatch"; rule INT-PAY-UNIFORM-404-UNKNOWN, LP-08-D10, ADR-025 item 5); `If-None-Match` 304 is computed from current state, so a revoked mandate no longer returns a stale 304 |
| `DELETE /open-finance/v1/vrp/payment-consents/{consentId}?reason=` (`revokeConsent`) | same | none | 401 without a valid token; unknown and another TPP's id: the same 404 `NOT_FOUND` "Consent not found" (another TPP's was 403; rule INT-PAY-UNIFORM-404-UNKNOWN, LP-08-D10); 409 `CONCURRENT_UPDATE` if a concurrent change wins; second revoke stays 204 and raises no event |
| `POST /open-finance/v1/vrp/payments` (`submitPayment`) | same | none | 401 without a valid token; a revoked or expired mandate is 403 "Consent Revoked" / "Consent expired" as in the monolith, also for a reused `x-idempotency-key` (the mandate's own state is checked before the idempotent replay; LP-08-U01); a collection on an unknown or another TPP's mandate (`ConsentId` in the body) is one 403 "Consent not found or not authorised" (monolith: 404 unknown, 403 "Consent participant mismatch"; LP-08-U02, rule INT-PAY-UNIFORM-404-UNKNOWN proposed: ADR-025 item 5 covers path ids, so body ids keep the 403 until governance rules on them); 403 once the PSU withdrew the consent in the consent service; 503 when the consent service is down; 400 for extra decimals or a debtor account that cannot be debited (same "DebtorAccount cannot be used for this mandate" body); 503 if the accounts API is unreachable; 409 `CONCURRENT_UPDATE` |
| `GET /open-finance/v1/vrp/payments/{paymentId}` (`getPayment`) | same | none | 401 without a valid token; unknown and another TPP's id: the same 404 `NOT_FOUND` "Payment not found" (another TPP's was 403 "Consent participant mismatch"; rule INT-PAY-UNIFORM-404-UNKNOWN, LP-08-D10) |
| any other path | not served | - | 401 anonymous, 403 authenticated (`denyAll`) |

Missing required headers or a malformed body were 500 `INTERNAL_ERROR` in the
monolith (caught by its `Exception` handler); they are now 400 `INVALID_REQUEST`.
An unsupported method or media type was 500 as well; it is now 405 / 415. A
path the firewall rejects is 400, not 401/403. The DPoP `htu` is the public URL
built from the gateway's `X-Forwarded-Proto/Host/Port`.

## Parity run findings (LP-08, run 2026-10-08 on e608975)

| Scenario | Finding | Status |
|---|---|---|
| LP-08-U01 | a collection's `x-idempotency-key` reused after DELETE: monolith 403 "Consent Revoked", service 201 with the stored payment (f333741 ran the replay first) | fixed in 3fb136b: the mandate's own revoked or expired state is checked before the replay, so the answer is the monolith's 403 "Consent Revoked" / "Consent expired"; the replay stays ahead of the remote consent-auth and accounts checks |
| LP-08-D10 | unknown and another TPP's mandate or payment path ids | rule INT-PAY-UNIFORM-404-UNKNOWN: one 404, "Consent not found" / "Payment not found" (ADR-025 item 5); bodies byte-identical (c096d0c) |
| LP-08-U02 | collection on an unknown or another TPP's mandate (`ConsentId` in the body) | rule INT-PAY-UNIFORM-404-UNKNOWN proposed; the service keeps one 403 "Consent not found or not authorised" while ADR-025 item 5 covers path ids only |

## Intentional behaviour changes

| Area | Monolith | New service |
|---|---|---|
| Mandate authorisation | the TPP's request created an AUTHORISED mandate for any `PsuId` | only under a consent the PSU authorised in consent-authorization-service; PSU and debtor account come from that consent; re-checked before every collection |
| TPP identity | `x-fapi-financial-id` header, `UNKNOWN_TPP` when absent; any `Bearer`/`DPoP` string accepted | Validated JWT (issuer, signature, `aud` must contain `svc-pay-recurring-mandates`); TPP = `azp` (else `client_id`); a different `x-fapi-financial-id` is 403 |
| DPoP | header required, not validated; `Bearer` scheme accepted | enforced (RFC 9449): `DPoP` scheme, token bound by `cnf.jkt`, proof verified (signature with its jwk, `htm`, `htu`, `iat` within 5 min, `ath`) and its `jti` single-use across pods (`dpop_proof_jti`); a plain `Bearer` token or a missing, replayed or mismatching proof is 401 `WWW-Authenticate: DPoP error="invalid_dpop_proof"` |
| Persistence | in-memory only; lost on restart; per-pod state | PostgreSQL `sc_pay_recurring_mandates`; shared by all pods |
| Concurrency | JVM lock per pod | PostgreSQL advisory lock per mandate plus version compare-and-set |
| Idempotency | in-memory per pod | `mandate_idempotency_record`, unique per TPP and key, claimed atomically; TTL 24 h |
| Expiry at creation | not checked | must be in the future |
| Money | `BigDecimal` and a string, any scale | `Money`: ISO 4217, scale fixed to the currency's minor units, more decimals refused |
| Debtor account | not modelled | optional; checked through the accounts API at creation and before each collection; fails closed |
| Events | none | `Payments.Mandate.{Created,Revoked,PaymentAccepted}.v1` on the aggregate topic `evt.pay.mandate.v1` through a transactional outbox |
| Response headers | `X-OF-Cache: HIT/MISS` | removed (cache is internal) |

## Not carried over

- Mandates and payments held in a monolith pod's memory at cutover; TPPs re-create mandates (no backfill, see runbook section 1).
- Monolith `integrationTest` / `functionalTest` source sets for this capability (they wire the in-memory adapters); their scenarios are covered by `RecurringMandatesServiceIT` and the controller tests.
