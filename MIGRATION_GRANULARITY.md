# Migration Granularity Notes

- Repository: `fintechbankx-payments-recurring-mandates-service`
- Source monorepo: `enterprise-loan-management-system`
- Sync date: `2026-03-15`
- Sync branch: `chore/granular-source-sync-20260313`

## Applied Rules

- capability extraction: `recurringpayments` from `open-finance-context`
- dir: `infra/terraform/services/recurring-payments-service` -> `infra/terraform/recurring-payments-service`
- file: `docs/architecture/open-finance/capabilities/hld/open-finance-capability-overview.md` -> `docs/hld/open-finance-capability-overview.md`
- file: `docs/architecture/open-finance/capabilities/test-suites/recurring-payments-test-suite.md` -> `docs/test-suites/recurring-payments-test-suite.md`

## Notes

- This is an extraction seed for bounded-context split migration.
- Follow-up refactoring may be needed to remove residual cross-context coupling.
- Build artifacts and local machine files are excluded by policy.


## 2026-10-08: deployable service (Proposed)

- Seeded open-finance residue outside `recurringpayments` removed; see README "Removed from this repository".
- `infra/terraform/recurring-payments-service` replaced by `deploy/terraform`; Helm chart in `deploy/helm/payment-recurring-mandates-service`.
- Own PostgreSQL schema `sc_pay_recurring_mandates` (Flyway V1, V2). The monolith held this capability in memory only, so there is no backfill and no data-split job.
- Monolith-to-service behaviour differences: `docs/migration/REGRESSION_MAPPING.md`.
