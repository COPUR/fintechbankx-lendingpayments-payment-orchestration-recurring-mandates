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

