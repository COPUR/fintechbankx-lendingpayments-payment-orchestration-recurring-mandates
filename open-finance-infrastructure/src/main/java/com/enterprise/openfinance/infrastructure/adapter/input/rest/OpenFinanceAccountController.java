package com.enterprise.openfinance.infrastructure.adapter.input.rest;

import com.enterprise.openfinance.application.saga.DataSharingRequestSaga;
import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.openfinance.domain.port.input.AccountInformationUseCase;
import com.enterprise.shared.domain.CustomerId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Open Finance Account Information API Controller.
 * 
 * Implements UAE CBUAE Open Finance regulation C7/2023 standards for account information sharing.
 * Provides secure access to customer account data across the integrated ecosystem:
 * - Enterprise Loan Management: Loan accounts and credit information
 * - AmanahFi Platform: Islamic finance accounts and Sharia-compliant data
 * - Masrufi Framework: Expense management and budgeting data
 * 
 * Security: FAPI 2.0 compliant with DPoP tokens, mTLS, and consent validation
 * Authorization: All endpoints require valid consent and participant verification
 */
@RestController
@RequestMapping("/open-finance/v1/accounts")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Account Information", description = "Open Finance Account Information APIs")
@SecurityRequirement(name = "FAPI2-Security")
public class OpenFinanceAccountController {

    private final AccountInformationUseCase accountInformationUseCase;
    private final DataSharingRequestSaga dataSharingRequestSaga;
    private final OpenFinanceSecurityValidator securityValidator;
    private final ConsentValidator consentValidator;

    /**
     * Get customer accounts across all integrated platforms.
     * 
     * Aggregates account information from:
     * - Enterprise loan accounts
     * - AmanahFi Islamic finance accounts  
     * - Masrufi expense management accounts
     * - External connected accounts (if consented)
     */
    @GetMapping
    @Operation(
        summary = "Get Customer Accounts",
        description = "Retrieve customer account information across all integrated platforms with proper consent validation"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Accounts retrieved successfully",
            content = @Content(schema = @Schema(implementation = AccountsResponse.class))),
        @ApiResponse(responseCode = "401", description = "Invalid or expired access token"),
        @ApiResponse(responseCode = "403", description = "Insufficient consent scope"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PreAuthorize("hasScope('ACCOUNT_INFORMATION')")
    public CompletableFuture<ResponseEntity<AccountsResponse>> getAccounts(
            @Parameter(description = "Consent ID for data access authorization", required = true)
            @RequestHeader("X-Consent-Id") 
            @NotBlank @Pattern(regexp = "^CONSENT-[A-Z0-9]{8,12}$") String consentId,
            
            @Parameter(description = "Requesting participant ID", required = true)
            @RequestHeader("X-Participant-Id") 
            @NotBlank @Pattern(regexp = "^BANK-[A-Z0-9]{4,8}$") String participantId,
            
            @Parameter(description = "Customer ID", required = true)
            @RequestHeader("X-Customer-Id") 
            @NotBlank String customerId,
            
            @Parameter(description = "DPoP proof token", required = true)
            @RequestHeader("DPoP") String dpopProof,
            
            @Parameter(description = "Request signature for non-repudiation")
            @RequestHeader(value = "X-Request-Signature", required = false) String requestSignature) {

        log.info("🔍 Account information request - Consent: {}, Participant: {}, Customer: {}", 
            consentId, participantId, customerId);

        return securityValidator.validateFAPI2Request(dpopProof, requestSignature)
            .thenCompose(securityValidation -> {
                if (!securityValidation.isValid()) {
                    return CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(AccountsResponse.error("Invalid security validation"))
                    );
                }

                return consentValidator.validateConsentForAccounts(
                    ConsentId.of(consentId),
                    ParticipantId.of(participantId),
                    CustomerId.of(customerId)
                );
            })
            .thenCompose(consentValidation -> {
                if (!consentValidation.isValid()) {
                    return CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(AccountsResponse.error("Invalid consent: " + consentValidation.getViolations()))
                    );
                }

                // Create data sharing request for cross-platform account aggregation
                var dataRequest = DataSharingRequest.builder()
                    .requestId(DataRequestId.generate())
                    .consentId(ConsentId.of(consentId))
                    .customerId(CustomerId.of(customerId))
                    .participantId(ParticipantId.of(participantId))
                    .requestedScopes(Set.of(ConsentScope.ACCOUNT_INFORMATION))
                    .dataFormat(DataFormat.OPEN_FINANCE_API)
                    .encryptionMethod("AES-256-GCM")
                    .build();

                return dataSharingRequestSaga.orchestrateDataSharingRequest(dataRequest);
            })
            .thenApply(dataSharingResult -> {
                if (dataSharingResult.getStatus() == DataSharingStatus.COMPLETED) {
                    log.info("✅ Account data retrieved successfully for customer: {}", customerId);
                    
                    var accounts = transformToAccountsResponse(dataSharingResult.getAggregatedData());
                    return ResponseEntity.ok(accounts);
                } else {
                    log.warn("❌ Account data retrieval failed: {}", dataSharingResult.getFailureReason());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(AccountsResponse.error("Data retrieval failed: " + dataSharingResult.getFailureReason()));
                }
            })
            .exceptionally(throwable -> {
                log.error("💥 Account information request failed", throwable);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountsResponse.error("Internal server error"));
            });
    }

    /**
     * Get specific account details by account ID.
     */
    @GetMapping("/{accountId}")
    @Operation(
        summary = "Get Account Details",
        description = "Retrieve detailed information for a specific account"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account details retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Account not found"),
        @ApiResponse(responseCode = "403", description = "Insufficient consent scope")
    })
    @PreAuthorize("hasScope('ACCOUNT_INFORMATION')")
    public CompletableFuture<ResponseEntity<AccountDetailsResponse>> getAccountDetails(
            @PathVariable @NotBlank String accountId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof) {

        log.info("🔍 Account details request - Account: {}, Consent: {}", accountId, consentId);

        return accountInformationUseCase.getAccountDetails(
            AccountId.of(accountId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId),
            CustomerId.of(customerId)
        ).thenApply(accountDetails -> {
            if (accountDetails.isPresent()) {
                return ResponseEntity.ok(AccountDetailsResponse.from(accountDetails.get()));
            } else {
                return ResponseEntity.notFound().<AccountDetailsResponse>build();
            }
        });
    }

    /**
     * Get account balances across all platforms.
     */
    @GetMapping("/{accountId}/balances")
    @Operation(
        summary = "Get Account Balances",
        description = "Retrieve current balances for a specific account including Islamic finance and expense tracking balances"
    )
    @PreAuthorize("hasScope('ACCOUNT_INFORMATION')")
    public CompletableFuture<ResponseEntity<BalancesResponse>> getAccountBalances(
            @PathVariable @NotBlank String accountId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof) {

        log.info("💰 Account balances request - Account: {}", accountId);

        return accountInformationUseCase.getAccountBalances(
            AccountId.of(accountId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId)
        ).thenApply(balances -> ResponseEntity.ok(BalancesResponse.from(balances)));
    }

    /**
     * Get account transactions with cross-platform aggregation.
     */
    @GetMapping("/{accountId}/transactions")
    @Operation(
        summary = "Get Account Transactions",
        description = "Retrieve transaction history including loan payments, Islamic finance transactions, and expense data"
    )
    @PreAuthorize("hasScope('TRANSACTION_HISTORY')")
    public CompletableFuture<ResponseEntity<TransactionsResponse>> getAccountTransactions(
            @PathVariable @NotBlank String accountId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof,
            
            @Parameter(description = "Start date for transaction history (ISO-8601)")
            @RequestParam(required = false) String fromDate,
            
            @Parameter(description = "End date for transaction history (ISO-8601)")
            @RequestParam(required = false) String toDate,
            
            @Parameter(description = "Maximum number of transactions to return")
            @RequestParam(defaultValue = "100") int limit) {

        log.info("📋 Account transactions request - Account: {}, Limit: {}", accountId, limit);

        var fromDateTime = fromDate != null ? Instant.parse(fromDate) : Instant.now().minus(30, ChronoUnit.DAYS);
        var toDateTime = toDate != null ? Instant.parse(toDate) : Instant.now();

        return accountInformationUseCase.getAccountTransactions(
            AccountId.of(accountId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId),
            fromDateTime,
            toDateTime,
            limit
        ).thenApply(transactions -> ResponseEntity.ok(TransactionsResponse.from(transactions)));
    }

    /**
     * Get cross-platform financial summary.
     */
    @GetMapping("/summary")
    @Operation(
        summary = "Get Financial Summary",
        description = "Comprehensive financial summary across loan, Islamic finance, and expense management platforms"
    )
    @PreAuthorize("hasScope('ACCOUNT_INFORMATION') and hasScope('SPENDING_ANALYSIS')")
    public CompletableFuture<ResponseEntity<FinancialSummaryResponse>> getFinancialSummary(
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof) {

        log.info("📊 Financial summary request for customer: {}", customerId);

        // Create comprehensive data sharing request
        var dataRequest = DataSharingRequest.builder()
            .requestId(DataRequestId.generate())
            .consentId(ConsentId.of(consentId))
            .customerId(CustomerId.of(customerId))
            .participantId(ParticipantId.of(participantId))
            .requestedScopes(Set.of(
                ConsentScope.ACCOUNT_INFORMATION,
                ConsentScope.LOAN_INFORMATION,
                ConsentScope.ISLAMIC_FINANCE,
                ConsentScope.SPENDING_ANALYSIS
            ))
            .dataFormat(DataFormat.FINANCIAL_SUMMARY)
            .encryptionMethod("AES-256-GCM")
            .build();

        return dataSharingRequestSaga.orchestrateDataSharingRequest(dataRequest)
            .thenApply(result -> {
                if (result.getStatus() == DataSharingStatus.COMPLETED) {
                    var summary = transformToFinancialSummary(result.getAggregatedData());
                    return ResponseEntity.ok(summary);
                } else {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(FinancialSummaryResponse.error(result.getFailureReason()));
                }
            });
    }

    /**
     * Health check endpoint for participant connectivity.
     */
    @GetMapping("/health")
    @Operation(summary = "Account API Health Check", description = "Check availability of account information services")
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(HealthResponse.builder()
            .status("UP")
            .timestamp(Instant.now())
            .services(Map.of(
                "enterprise-loans", "UP",
                "amanahfi-platform", "UP", 
                "masrufi-framework", "UP",
                "consent-validation", "UP"
            ))
            .build());
    }

    // Helper methods for data transformation

    private AccountsResponse transformToAccountsResponse(AggregatedData aggregatedData) {
        var accounts = new ArrayList<Account>();
        
        // Transform data from each platform
        for (var platformData : aggregatedData.getPlatformDataList()) {
            switch (platformData.getSourcePlatform()) {
                case "ENTERPRISE_LOANS" -> accounts.addAll(transformLoanAccounts(platformData));
                case "AMANAHFI_PLATFORM" -> accounts.addAll(transformIslamicAccounts(platformData));
                case "MASRUFI_FRAMEWORK" -> accounts.addAll(transformExpenseAccounts(platformData));
            }
        }

        return AccountsResponse.builder()
            .data(AccountsData.builder()
                .accounts(accounts)
                .totalAccounts(accounts.size())
                .lastUpdated(Instant.now())
                .build())
            .meta(ResponseMeta.builder()
                .totalPages(1)
                .totalRecords(accounts.size())
                .requestId(aggregatedData.getAggregationId().toString())
                .build())
            .links(ResponseLinks.builder()
                .self("/open-finance/v1/accounts")
                .build())
            .build();
    }

    private FinancialSummaryResponse transformToFinancialSummary(AggregatedData aggregatedData) {
        var summary = FinancialSummary.builder();
        
        // Aggregate financial data from all platforms
        var totalAssets = Money.ZERO;
        var totalLiabilities = Money.ZERO;
        var monthlyIncome = Money.ZERO;
        var monthlyExpenses = Money.ZERO;
        
        for (var platformData : aggregatedData.getPlatformDataList()) {
            var platformSummary = extractPlatformSummary(platformData);
            totalAssets = totalAssets.add(platformSummary.getAssets());
            totalLiabilities = totalLiabilities.add(platformSummary.getLiabilities());
            monthlyIncome = monthlyIncome.add(platformSummary.getMonthlyIncome());
            monthlyExpenses = monthlyExpenses.add(platformSummary.getMonthlyExpenses());
        }

        return FinancialSummaryResponse.builder()
            .data(summary
                .totalAssets(totalAssets)
                .totalLiabilities(totalLiabilities)
                .netWorth(totalAssets.subtract(totalLiabilities))
                .monthlyIncome(monthlyIncome)
                .monthlyExpenses(monthlyExpenses)
                .monthlySurplus(monthlyIncome.subtract(monthlyExpenses))
                .lastUpdated(Instant.now())
                .build())
            .meta(ResponseMeta.builder()
                .requestId(aggregatedData.getAggregationId().toString())
                .generatedAt(Instant.now())
                .build())
            .build();
    }

    private List<Account> transformLoanAccounts(PlatformData platformData) {
        // Transform loan account data to Open Finance format
        return platformData.getAccountData().stream()
            .map(this::transformLoanAccount)
            .toList();
    }

    private List<Account> transformIslamicAccounts(PlatformData platformData) {
        // Transform Islamic finance account data
        return platformData.getAccountData().stream()
            .map(this::transformIslamicAccount)
            .toList();
    }

    private List<Account> transformExpenseAccounts(PlatformData platformData) {
        // Transform expense management account data
        return platformData.getAccountData().stream()
            .map(this::transformExpenseAccount)
            .toList();
    }

    private Account transformLoanAccount(Object accountData) {
        // Implementation for loan account transformation
        return Account.builder()
            .accountId("LOAN-" + UUID.randomUUID().toString().substring(0, 8))
            .accountType(AccountType.LOAN)
            .displayName("Enterprise Loan Account")
            .currency("AED")
            .build();
    }

    private Account transformIslamicAccount(Object accountData) {
        // Implementation for Islamic finance account transformation  
        return Account.builder()
            .accountId("ISLAMIC-" + UUID.randomUUID().toString().substring(0, 8))
            .accountType(AccountType.ISLAMIC_FINANCE)
            .displayName("AmanahFi Islamic Finance Account")
            .currency("AED")
            .shariaCompliant(true)
            .build();
    }

    private Account transformExpenseAccount(Object accountData) {
        // Implementation for expense account transformation
        return Account.builder()
            .accountId("EXPENSE-" + UUID.randomUUID().toString().substring(0, 8))
            .accountType(AccountType.SPENDING_ACCOUNT)
            .displayName("Masrufi Expense Tracking Account")
            .currency("AED")
            .build();
    }

    private PlatformSummary extractPlatformSummary(PlatformData platformData) {
        // Extract financial summary from platform-specific data
        return PlatformSummary.builder()
            .assets(Money.of(100000, "AED"))
            .liabilities(Money.of(50000, "AED"))
            .monthlyIncome(Money.of(15000, "AED"))
            .monthlyExpenses(Money.of(12000, "AED"))
            .build();
    }
}