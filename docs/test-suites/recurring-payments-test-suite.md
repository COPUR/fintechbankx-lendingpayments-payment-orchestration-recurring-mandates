# Test Suite: Variable Recurring Payments (VRP)
**Scope:** Recurring Payments
**Actors:** TPP, PSU, Mandate Engine

## 1. Prerequisites
* Active VRP Consent (Mandate) setup with Limit: 5000 AED/Month.

## 2. Test Cases

### Suite A: Mandate & Sweeping
| ID | Test Case Description | Input Data | Expected Result | Type |
|----|-----------------------|------------|-----------------|------|
| **TC-VRP-001** | Create VRP Consent | Limit: 5000/Month | `201 Created`, Status: `Authorised` | Functional |
| **TC-VRP-002** | Trigger Payment Within Limit | Amount: 100 AED | `201 Created`, Payment Executed | Functional |
| **TC-VRP-003** | Trigger Payment Exceeding Limit | Amount: 5001 AED | `400 Bad Request`, Error: `Limit Exceeded` | Functional |
| **TC-VRP-004** | Cumulative Limit Check | Trigger 5 x 1001 AED | 5th transaction fails (Total > 5000) | Functional |
| **TC-VRP-005** | Revoke Mandate | Call DELETE /consents | `204 No Content` | Functional |
| **TC-VRP-006** | Trigger on Revoked Mandate | Amount: 10 AED | `403 Forbidden`, Error: `Consent Revoked` | Negative |

### Suite B: Concurrency
| ID | Test Case Description | Input Data | Expected Result | Type |
|----|-----------------------|------------|-----------------|------|
| **TC-VRP-007** | Double Trigger Race Condition | Send 2 reqs of 3000 AED simultaneously (Limit 5000) | Only 1 succeeds, 2nd fails (Locking mechanism verified) | Security |
