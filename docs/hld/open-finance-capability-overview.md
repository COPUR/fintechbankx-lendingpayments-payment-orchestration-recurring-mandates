# Open Finance Capability Architecture Overview

## Common Architectural Principles (Applicable to All Capabilities)

Before detailing each capability, note that they all share a common Open Finance architectural pattern:

* **Security:** OAuth 2.1 / OIDC with FAPI 2.0 controls (DPoP-bound tokens, PAR, PKCE, mTLS, required FAPI interaction headers).
* **Actors:** PSU (Payment Service User), TPP (Third Party Provider), ASPSP (Account Servicing Payment Service Provider).
* **Core Components:** API Gateway, Consent Manager, Identity Provider (IdP), and backend resource services.
* **Data Pattern:** PostgreSQL as transactional/event source of truth, Redis for low-latency controls/caching, MongoDB as analytics silver copy where required.
* **API Baseline:** Resource calls require `Authorization: DPoP <token>`, `DPoP`, and `X-FAPI-Interaction-ID`; write calls also require idempotency keys.

---

## 1. Personal Financial Management (Personal Banking Data)

**Description:** Enables retail customers to share their account balances and transaction history with TPPs for budgeting and analytics.

**System Architecture & Modules:**

* **Consent Module:** Handles the granular permissions for "ReadAccounts", "ReadBalances", and "ReadTransactions".
* **Account Information Service (AIS):** Retrieves read-only data from the Core Banking System.

**Data Flow:**

1. TPP requests account access consent.
2. User authenticates and authorizes specific permissions.
3. TPP exchanges auth code for Access Token.
4. TPP requests `/accounts`, `/balances`, or `/transactions` using the token.

**API Signature:**

```http
POST /account-access-consents
GET  /account-access-consents/{ConsentId}
GET  /accounts
GET  /accounts/{AccountId}
GET  /accounts/{AccountId}/balances
GET  /accounts/{AccountId}/transactions
GET  /accounts/{AccountId}/beneficiaries
GET  /accounts/{AccountId}/direct-debits
GET  /accounts/{AccountId}/standing-orders
```

**Postman Collection Structure:**

* **Folder: Personal Financial Management**
* `POST Create Account Access Consent`
* `GET Retrieve Consent Status`
* `GET All Accounts`
* `GET Account Balances`
* `GET Account Transactions`

---

## 2. Business Financial Management (Corporate Banking Data)

**Description:** Similar to Personal Financial Management but tailored for SMEs and Corporates, focusing on cash flow, tax visualization, and P&L updates.

**System Architecture & Modules:**

* **Corporate Consent Module:** May require multi-authorization (e.g., Maker-Checker) workflows depending on bank policy.
* **Reconciliation Engine:** Feeds data into ERP/Accounting software via TPP.

**Data Flow:**

* Standard AIS flow, but data payload may include business-specific fields (e.g., extended transaction codes).

**API Signature:**

```http
POST /account-access-consents
GET  /accounts
GET  /balances
GET  /transactions
GET  /scheduled-payments
GET  /parties (Corporate Entity Details)
```

**Postman Collection Structure:**

* **Folder: Business Financial Management**
* `POST Create Business Consent`
* `GET Corporate Accounts`
* `GET Corporate Balances`
* `GET Business Transactions`

---

## 3. Confirmation of Payee (Banking Customer Details)

**Description:** A verification service to check if the account name matches the account number before making a payment, reducing fraud (APP scams) and misdirected payments.

**System Architecture & Modules:**

* **Name Matching Engine:** Uses fuzzy logic algorithms (e.g., Levenshtein distance) to compare the input name vs. the registered account name.
* **Directory Service:** Looks up account status (Active, Dormant, Deceased).

**Data Flow:**

1. TPP sends `POST /confirmation` with Sort Code, Account Number, and Name.
2. Bank checks details and returns a match result: `Match`, `Close Match`, `No Match`, or `Unable to Check`.

**API Signature:**

```http
POST /discovery (Optional: To discover capability)
POST /confirmation
```

**Postman Collection Structure:**

* **Folder: Confirmation of Payee**
* `POST Check Payee Details (Exact Match)`
* `POST Check Payee Details (No Match)`
* `POST Check Payee Details (Close Match)`

---

## 4. Banking Metadata (Metadata Details)

**Description:** Provides auxiliary data related to transactions, products, or customers, such as merchant geo-location or fee tariffs.

**System Architecture & Modules:**

* **Metadata Repository:** A read-only store containing static or semi-static data (branch locations, product fees).
* **Enrichment Service:** Appends metadata to standard transaction records.

**API Signature:**

```http
GET /accounts/{AccountId}/product (Tariff/Fee info)
GET /accounts/{AccountId}/parties (Customer relationship length)
GET /beneficiaries
GET /standing-orders
```

**Postman Collection Structure:**

* **Folder: Banking Metadata**
* `GET Account Product Details`
* `GET Account Parties Information`

---

## 5. Corporate Treasury Data

**Description:** Advanced data retrieval for Corporate Treasury, including sweeping arrangements, virtual accounts, and liquidity positions.

**System Architecture & Modules:**

* **Virtual Account Management System (VAMS):** Interface to fetch virtual IBAN structures.
* **Treasury Management System (TMS) Adapter:** Connects to the bank's backend TMS.

**API Signature:**

```http
GET /accounts (Includes Virtual Accounts)
GET /balances (Real-time cash position)
GET /transactions (Including sweeping history)
GET /direct-debits
```

**Postman Collection Structure:**

* **Folder: Corporate Treasury Data**
* `GET Virtual Accounts`
* `GET Real-time Liquidity Balances`
* `GET Sweeping Transactions`

---

## 6. Payment Initiation (Single/Future & International Payments)

**Description:** Enables TPPs to initiate immediate, future-dated, or cross-border payments.

**System Architecture & Modules:**

* **Payment Initiation Service (PIS):** Validates the payment instruction and funds availability.
* **Payment Engine:** Interfaces with local rails (e.g., Faster Payments) or SWIFT/SEPA for international.
* **FX Rate Service:** Provides exchange rates for international payments.

**Data Flow:**

1. TPP sets up `payment-consent` with amount, payee, and date.
2. User authorizes consent.
3. TPP submits the `payment` instruction.

**API Signature:**

```http
GET  /payment-consents/{ConsentId}
POST /payment-consents
POST /payments
GET  /payments/{PaymentId}
POST /file-payments (For bulk instructions)
GET  /file-payments/{PaymentId}/report
```

**Postman Collection Structure:**

* **Folder: Payment Initiation**
* `POST Create Single Payment Consent`
* `POST Submit Payment`
* `POST Create International Payment Consent`
* `POST Submit File Payment`
* `GET Payment Status`

---

## 7. Recurring Payments (Variable Recurring Payments)

**Description:** Enables Standing Orders (fixed) and VRPs (Variable Recurring Payments) for sweeping or subscriptions.

**System Architecture & Modules:**

* **Mandate Management System:** Stores the VRP parameters (max amount per period, expiry date).
* **Subscription Engine:** Checks payment requests against the stored mandate limits.

**API Signature:**

```http
POST /payment-consents (Specific type: VRP)
GET  /payment-consents/{ConsentId}
POST /payments (Initiate a VRP collection)
GET  /payments/{PaymentId}
```

**Postman Collection Structure:**

* **Folder: Recurring Payments**
* `POST Create VRP Consent`
* `POST Initiate VRP Collection`
* `GET VRP Payment Status`

---

## 8. Corporate Bulk Payments (Bulk Payments)

**Description:** Batch processing for payroll or supplier payments, allowing a shift from traditional batch files to API-driven real-time processing.

**System Architecture & Modules:**

* **Bulk Validator:** Checks file formats (XML, JSON, CSV) and individual line items.
* **Batch Processor:** Decomposes the bulk file into individual payment instructions.

**API Signature:**

```http
POST /payment-consents/{ConsentId}/file
POST /file-payments
GET  /file-payments/{PaymentId}
GET  /file-payments/{PaymentId}/report
```

**Postman Collection Structure:**

* **Folder: Corporate Bulk Payments**
* `POST Upload Payment File`
* `POST Initiate Bulk Payment`
* `GET Bulk Payment Report`

---

## 9. Insurance Data Sharing

**Description:** Allows users to share their insurance policy details (Motor, Health, Home) with TPPs for comparison or aggregation.

**System Architecture & Modules:**

* **Policy Admin System Adapter:** Connects to legacy insurance backends.
* **Insurance API Gateway:** Standardizes data models across different insurance lines (e.g., Motor vs. Health).

**API Signature:**

```http
GET /insurance-consents
GET /motor-insurance-policies
GET /motor-insurance-policies/{PolicyId}
GET /health-insurance-policies
GET /home-insurance-policies
GET /life-insurance-policies
```

**Postman Collection Structure:**

* **Folder: Insurance Data Sharing**
* `POST Create Insurance Consent`
* `GET Motor Policies`
* `GET Health Policies`
* `GET Home Policies`

---

## 10. Insurance Quote Initiation

**Description:** Enables TPPs to request insurance quotes on behalf of the customer based on their profile data.

**System Architecture & Modules:**

* **Rating Engine:** Calculates premiums based on risk factors passed in the API.
* **Quote Repository:** Stores generated quotes for a validity period.

**API Signature:**

```http
POST /motor-insurance-quotes
GET  /motor-insurance-quotes/{QuoteId}
POST /health-insurance-quotes
POST /home-insurance-quotes
POST /travel-insurance-quotes
```

**Postman Collection Structure:**

* **Folder: Insurance Quote Initiation**
* `POST Request Motor Quote`
* `GET Retrieve Quote Details`
* `POST Request Travel Quote`

---

## 11. FX and Remittance Services

**Description:** Allows TPPs to request "actionable quotes" for FX trades and remittances from multiple LFIs/Exchange Houses.

**System Architecture & Modules:**

* **Treasury Pricing Engine:** Provides real-time streaming FX rates.
* **Booking System:** Reserves the rate for a specific window (e.g., 30 seconds).

**API Signature:**

```http
POST /fx-quotes (Request a quote)
GET  /fx-quotes/{FxQuoteId}
PATCH /fx-quotes/{FxQuoteId} (Accept/Reject quote)
```

**Postman Collection Structure:**

* **Folder: FX and Remittance Services**
* `POST Get FX Quote`
* `GET Check Quote Status`
* `PATCH Accept Quote`

---

## 12. Dynamic Onboarding (Account Opening for FX Services)

**Description:** Enables a user to open an account on the fly with an LFI to execute an FX quote if they don't already have a relationship.

**System Architecture & Modules:**

* **e-KYC Module:** Handles identity verification electronically.
* **Onboarding Workflow:** Orchestrates the creation of the customer record and account generation.

**API Signature:**

```http
POST /accounts (Create new account context)
GET  /accounts/{AccountId} (Verify creation)
```

**Postman Collection Structure:**

* **Folder: Dynamic Onboarding**
* `POST Open New Account`
* `GET Account Details`

---

## 13. Request to Pay

**Description:** Enables a creditor (payee) to send a payment request to a debtor (payer). The debtor receives the notification and can initiate the payment.

**System Architecture & Modules:**

* **RtP (Request to Pay) Repository:** Stores pending requests.
* **Notification Service:** Pushes the request to the debtor's banking app.

**API Signature:**

```http
POST /par (Payment Authorization Request / Pay Request)
GET  /payment-consents/{ConsentId}
POST /payments (Triggered by Debtor accepting the request)
```

**Postman Collection Structure:**

* **Folder: Request to Pay**
* `POST Initiate Pay Request`
* `GET Pay Request Status`

---

## 14. Open Products Data (Open Data)

**Description:** Publicly available data about banking products (loans, cards, accounts) to facilitate market comparison. No user authentication required usually.

**System Architecture & Modules:**

* **Product Catalog:** A structured database of all bank offerings, rates, and T&Cs.
* **Public API Endpoint:** Unsecured (or lightly secured) endpoint for TPPs.

**API Signature:**

```http
GET /products
POST /leads (Optional: To register interest)
```

**Postman Collection Structure:**

* **Folder: Open Products Data**
* `GET All Products`
* `GET Product Details`

---

## 15. ATM Open Data (Open Data)

**Description:** Public data providing real-time location, status, and features of ATMs.

**System Architecture & Modules:**

* **ATM Switch/Monitor:** Real-time feed of ATM operational status (In Service/Out of Order).
* **Geo-Location Service:** Provides latitude/longitude coordinates.

**API Signature:**

```http
GET /atms
```

**Postman Collection Structure:**

* **Folder: ATM Open Data**
* `GET ATM Locations`
* `GET specific ATM Details`
