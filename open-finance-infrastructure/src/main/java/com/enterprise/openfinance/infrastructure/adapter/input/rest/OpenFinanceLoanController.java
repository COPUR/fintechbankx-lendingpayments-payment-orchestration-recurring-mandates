package com.enterprise.openfinance.infrastructure.adapter.input.rest;

import com.enterprise.openfinance.application.saga.DataSharingRequestSaga;
import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.consent.ConsentScope;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.openfinance.domain.port.input.LoanInformationUseCase;
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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Open Finance Loan Information API Controller.
 * 
 * Provides secure access to loan and credit information across the integrated ecosystem:
 * - Enterprise Loan Management: Traditional loans, credit facilities, mortgages
 * - AmanahFi Platform: Islamic finance products (Murabaha, Musharakah, Ijarah, etc.)
 * - Masrufi Framework: Expense-linked credit and budget-based lending
 * 
 * Implements UAE CBUAE Open Finance regulation C7/2023 for loan data sharing.
 * Uses distributed saga patterns for cross-platform data aggregation.
 * 
 * Security: FAPI 2.0 compliant with comprehensive consent validation
 */
@RestController
@RequestMapping("/open-finance/v1/loans")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Loan Information", description = "Open Finance Loan Information APIs")
@SecurityRequirement(name = "FAPI2-Security")
public class OpenFinanceLoanController {

    private final LoanInformationUseCase loanInformationUseCase;
    private final DataSharingRequestSaga dataSharingRequestSaga;
    private final OpenFinanceSecurityValidator securityValidator;
    private final ConsentValidator consentValidator;

    /**
     * Get customer loans across all integrated platforms.
     * 
     * Aggregates loan information from:
     * - Enterprise traditional loans and credit facilities
     * - AmanahFi Islamic finance products 
     * - Masrufi expense-linked credit products
     */
    @GetMapping
    @Operation(
        summary = "Get Customer Loans",
        description = "Retrieve comprehensive loan information across all integrated platforms including Islamic finance and expense-linked credit"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Loans retrieved successfully",
            content = @Content(schema = @Schema(implementation = LoansResponse.class))),
        @ApiResponse(responseCode = "401", description = "Invalid or expired access token"),
        @ApiResponse(responseCode = "403", description = "Insufficient consent scope"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @PreAuthorize("hasScope('LOAN_INFORMATION')")
    public CompletableFuture<ResponseEntity<LoansResponse>> getLoans(
            @Parameter(description = "Consent ID for loan data access", required = true)
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
            
            @Parameter(description = "Filter by loan status")
            @RequestParam(required = false) String status,
            
            @Parameter(description = "Include Islamic finance products")
            @RequestParam(defaultValue = "true") boolean includeIslamicFinance,
            
            @Parameter(description = "Include expense-linked credit")
            @RequestParam(defaultValue = "true") boolean includeExpenseCredit) {

        log.info("🏦 Loan information request - Consent: {}, Participant: {}, Customer: {}", 
            consentId, participantId, customerId);

        return securityValidator.validateFAPI2Request(dpopProof, null)
            .thenCompose(securityValidation -> {
                if (!securityValidation.isValid()) {
                    return CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(LoansResponse.error("Invalid security validation"))
                    );
                }

                return consentValidator.validateConsentForLoans(
                    ConsentId.of(consentId),
                    ParticipantId.of(participantId),
                    CustomerId.of(customerId),
                    includeIslamicFinance,
                    includeExpenseCredit
                );
            })
            .thenCompose(consentValidation -> {
                if (!consentValidation.isValid()) {
                    return CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(LoansResponse.error("Invalid consent: " + consentValidation.getViolations()))
                    );
                }

                // Build data sharing request with appropriate scopes
                var requestedScopes = buildLoanScopes(includeIslamicFinance, includeExpenseCredit);
                
                var dataRequest = DataSharingRequest.builder()
                    .requestId(DataRequestId.generate())
                    .consentId(ConsentId.of(consentId))
                    .customerId(CustomerId.of(customerId))
                    .participantId(ParticipantId.of(participantId))
                    .requestedScopes(requestedScopes)
                    .dataFormat(DataFormat.LOAN_INFORMATION)
                    .encryptionMethod("AES-256-GCM")
                    .filterCriteria(Map.of(
                        "status", status != null ? status : "ALL",
                        "includeIslamicFinance", includeIslamicFinance,
                        "includeExpenseCredit", includeExpenseCredit
                    ))
                    .build();

                return dataSharingRequestSaga.orchestrateDataSharingRequest(dataRequest);
            })
            .thenApply(dataSharingResult -> {
                if (dataSharingResult.getStatus() == DataSharingStatus.COMPLETED) {
                    log.info("✅ Loan data retrieved successfully for customer: {}", customerId);
                    
                    var loans = transformToLoansResponse(dataSharingResult.getAggregatedData());
                    return ResponseEntity.ok(loans);
                } else {
                    log.warn("❌ Loan data retrieval failed: {}", dataSharingResult.getFailureReason());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(LoansResponse.error("Data retrieval failed: " + dataSharingResult.getFailureReason()));
                }
            })
            .exceptionally(throwable -> {
                log.error("💥 Loan information request failed", throwable);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(LoansResponse.error("Internal server error"));
            });
    }

    /**
     * Get detailed information for a specific loan.
     */
    @GetMapping("/{loanId}")
    @Operation(
        summary = "Get Loan Details",
        description = "Retrieve detailed information for a specific loan including repayment schedule and Islamic finance details"
    )
    @PreAuthorize("hasScope('LOAN_INFORMATION')")
    public CompletableFuture<ResponseEntity<LoanDetailsResponse>> getLoanDetails(
            @PathVariable @NotBlank String loanId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof) {

        log.info("🔍 Loan details request - Loan: {}, Consent: {}", loanId, consentId);

        return loanInformationUseCase.getLoanDetails(
            LoanId.of(loanId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId),
            CustomerId.of(customerId)
        ).thenApply(loanDetails -> {
            if (loanDetails.isPresent()) {
                return ResponseEntity.ok(LoanDetailsResponse.from(loanDetails.get()));
            } else {
                return ResponseEntity.notFound().<LoanDetailsResponse>build();
            }
        });
    }

    /**
     * Get loan repayment schedule.
     */
    @GetMapping("/{loanId}/schedule")
    @Operation(
        summary = "Get Loan Repayment Schedule",
        description = "Retrieve repayment schedule including Islamic finance profit calculations and expense-linked payments"
    )
    @PreAuthorize("hasScope('LOAN_INFORMATION')")
    public CompletableFuture<ResponseEntity<RepaymentScheduleResponse>> getRepaymentSchedule(
            @PathVariable @NotBlank String loanId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof,
            
            @Parameter(description = "Include future projected payments")
            @RequestParam(defaultValue = "true") boolean includeFuture,
            
            @Parameter(description = "Include Islamic finance profit breakdown")
            @RequestParam(defaultValue = "true") boolean includeIslamicBreakdown) {

        log.info("📅 Repayment schedule request - Loan: {}", loanId);

        return loanInformationUseCase.getRepaymentSchedule(
            LoanId.of(loanId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId),
            includeFuture,
            includeIslamicBreakdown
        ).thenApply(schedule -> ResponseEntity.ok(RepaymentScheduleResponse.from(schedule)));
    }

    /**
     * Get early settlement calculation.
     */
    @GetMapping("/{loanId}/early-settlement")
    @Operation(
        summary = "Get Early Settlement Calculation",
        description = "Calculate early settlement amount including Islamic finance profit adjustments"
    )
    @PreAuthorize("hasScope('LOAN_INFORMATION')")
    public CompletableFuture<ResponseEntity<EarlySettlementResponse>> getEarlySettlement(
            @PathVariable @NotBlank String loanId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof,
            
            @Parameter(description = "Settlement date (defaults to today)")
            @RequestParam(required = false) String settlementDate) {

        log.info("💰 Early settlement calculation - Loan: {}", loanId);

        var settlementDateTime = settlementDate != null ? 
            Instant.parse(settlementDate) : Instant.now();

        return loanInformationUseCase.calculateEarlySettlement(
            LoanId.of(loanId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId),
            settlementDateTime
        ).thenApply(settlement -> ResponseEntity.ok(EarlySettlementResponse.from(settlement)));
    }

    /**
     * Get Islamic finance specific information.
     */
    @GetMapping("/{loanId}/islamic-finance")
    @Operation(
        summary = "Get Islamic Finance Details",
        description = "Retrieve Sharia-compliant finance details including profit sharing, asset backing, and compliance status"
    )
    @PreAuthorize("hasScope('ISLAMIC_FINANCE')")
    public CompletableFuture<ResponseEntity<IslamicFinanceDetailsResponse>> getIslamicFinanceDetails(
            @PathVariable @NotBlank String loanId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof) {

        log.info("🕌 Islamic finance details request - Loan: {}", loanId);

        return loanInformationUseCase.getIslamicFinanceDetails(
            LoanId.of(loanId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId)
        ).thenApply(islamicDetails -> {
            if (islamicDetails.isPresent()) {
                return ResponseEntity.ok(IslamicFinanceDetailsResponse.from(islamicDetails.get()));
            } else {
                return ResponseEntity.notFound().<IslamicFinanceDetailsResponse>build();
            }
        });
    }

    /**
     * Get loan performance analytics.
     */
    @GetMapping("/{loanId}/performance")
    @Operation(
        summary = "Get Loan Performance Analytics",
        description = "Retrieve loan performance metrics including payment history and expense correlation"
    )
    @PreAuthorize("hasScope('LOAN_INFORMATION') and hasScope('SPENDING_ANALYSIS')")
    public CompletableFuture<ResponseEntity<LoanPerformanceResponse>> getLoanPerformance(
            @PathVariable @NotBlank String loanId,
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof,
            
            @Parameter(description = "Analysis period in months")
            @RequestParam(defaultValue = "12") int months) {

        log.info("📈 Loan performance analytics - Loan: {}, Period: {} months", loanId, months);

        return loanInformationUseCase.getLoanPerformance(
            LoanId.of(loanId),
            ConsentId.of(consentId),
            ParticipantId.of(participantId),
            months
        ).thenApply(performance -> ResponseEntity.ok(LoanPerformanceResponse.from(performance)));
    }

    /**
     * Get cross-platform loan summary.
     */
    @GetMapping("/summary")
    @Operation(
        summary = "Get Loan Portfolio Summary",
        description = "Comprehensive loan portfolio summary across traditional, Islamic, and expense-linked credit"
    )
    @PreAuthorize("hasScope('LOAN_INFORMATION')")
    public CompletableFuture<ResponseEntity<LoanPortfolioSummaryResponse>> getLoanPortfolioSummary(
            @RequestHeader("X-Consent-Id") @NotBlank String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof) {

        log.info("📊 Loan portfolio summary for customer: {}", customerId);

        // Create comprehensive data sharing request for loan portfolio
        var dataRequest = DataSharingRequest.builder()
            .requestId(DataRequestId.generate())
            .consentId(ConsentId.of(consentId))
            .customerId(CustomerId.of(customerId))
            .participantId(ParticipantId.of(participantId))
            .requestedScopes(Set.of(
                ConsentScope.LOAN_INFORMATION,
                ConsentScope.ISLAMIC_FINANCE,
                ConsentScope.SPENDING_ANALYSIS
            ))
            .dataFormat(DataFormat.LOAN_PORTFOLIO_SUMMARY)
            .encryptionMethod("AES-256-GCM")
            .build();

        return dataSharingRequestSaga.orchestrateDataSharingRequest(dataRequest)
            .thenApply(result -> {
                if (result.getStatus() == DataSharingStatus.COMPLETED) {
                    var summary = transformToLoanPortfolioSummary(result.getAggregatedData());
                    return ResponseEntity.ok(summary);
                } else {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(LoanPortfolioSummaryResponse.error(result.getFailureReason()));
                }
            });
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    @Operation(summary = "Loan API Health Check")
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(HealthResponse.builder()
            .status("UP")
            .timestamp(Instant.now())
            .services(Map.of(
                "enterprise-loans", "UP",
                "amanahfi-islamic-finance", "UP",
                "masrufi-expense-credit", "UP",
                "consent-validation", "UP"
            ))
            .build());
    }

    // Helper methods

    private Set<ConsentScope> buildLoanScopes(boolean includeIslamicFinance, boolean includeExpenseCredit) {
        var scopes = new HashSet<ConsentScope>();
        scopes.add(ConsentScope.LOAN_INFORMATION);
        
        if (includeIslamicFinance) {
            scopes.add(ConsentScope.ISLAMIC_FINANCE);
            scopes.add(ConsentScope.SHARIA_COMPLIANCE);
        }
        
        if (includeExpenseCredit) {
            scopes.add(ConsentScope.SPENDING_ANALYSIS);
            scopes.add(ConsentScope.TRANSACTION_HISTORY);
        }
        
        return scopes;
    }

    private LoansResponse transformToLoansResponse(AggregatedData aggregatedData) {
        var loans = new ArrayList<Loan>();
        
        // Transform loans from each platform
        for (var platformData : aggregatedData.getPlatformDataList()) {
            switch (platformData.getSourcePlatform()) {
                case "ENTERPRISE_LOANS" -> loans.addAll(transformEnterpriseLoa
ns(platformData));
                case "AMANAHFI_PLATFORM" -> loans.addAll(transformIslamicFinanceProducts(platformData));
                case "MASRUFI_FRAMEWORK" -> loans.addAll(transformExpenseCreditProducts(platformData));
            }
        }

        return LoansResponse.builder()
            .data(LoansData.builder()
                .loans(loans)
                .totalLoans(loans.size())
                .totalOutstanding(calculateTotalOutstanding(loans))
                .totalMonthlyPayment(calculateTotalMonthlyPayment(loans))
                .lastUpdated(Instant.now())
                .build())
            .meta(ResponseMeta.builder()
                .totalRecords(loans.size())
                .requestId(aggregatedData.getAggregationId().toString())
                .platforms(aggregatedData.getDataSources())
                .build())
            .links(ResponseLinks.builder()
                .self("/open-finance/v1/loans")
                .summary("/open-finance/v1/loans/summary")
                .build())
            .build();
    }

    private LoanPortfolioSummaryResponse transformToLoanPortfolioSummary(AggregatedData aggregatedData) {
        var summary = LoanPortfolioSummary.builder();
        
        var traditionalLoans = Money.ZERO;
        var islamicFinance = Money.ZERO;
        var expenseCredit = Money.ZERO;
        var totalMonthlyPayments = Money.ZERO;
        
        // Aggregate loan data from all platforms
        for (var platformData : aggregatedData.getPlatformDataList()) {
            var platformSummary = extractLoanSummaryFromPlatform(platformData);
            
            switch (platformData.getSourcePlatform()) {
                case "ENTERPRISE_LOANS" -> traditionalLoans = traditionalLoans.add(platformSummary.getTotalOutstanding());
                case "AMANAHFI_PLATFORM" -> islamicFinance = islamicFinance.add(platformSummary.getTotalOutstanding());
                case "MASRUFI_FRAMEWORK" -> expenseCredit = expenseCredit.add(platformSummary.getTotalOutstanding());
            }
            
            totalMonthlyPayments = totalMonthlyPayments.add(platformSummary.getMonthlyPayment());
        }

        return LoanPortfolioSummaryResponse.builder()
            .data(summary
                .traditionalLoans(traditionalLoans)
                .islamicFinanceProducts(islamicFinance)
                .expenseCreditProducts(expenseCredit)
                .totalOutstanding(traditionalLoans.add(islamicFinance).add(expenseCredit))
                .totalMonthlyPayments(totalMonthlyPayments)
                .creditUtilization(calculateCreditUtilization(aggregatedData))
                .averageInterestRate(calculateAverageRate(aggregatedData))
                .shariaCompliantPercentage(calculateShariaCompliantPercentage(islamicFinance, traditionalLoans.add(islamicFinance).add(expenseCredit)))
                .lastUpdated(Instant.now())
                .build())
            .meta(ResponseMeta.builder()
                .requestId(aggregatedData.getAggregationId().toString())
                .platforms(aggregatedData.getDataSources())
                .generatedAt(Instant.now())
                .build())
            .build();
    }

    private List<Loan> transformEnterpriseLoan s(PlatformData platformData) {
        // Transform enterprise loan data to Open Finance format
        return platformData.getLoanData().stream()
            .map(this::transformEnterpriseLoan)
            .toList();
    }

    private List<Loan> transformIslamicFinanceProducts(PlatformData platformData) {
        // Transform AmanahFi Islamic finance products
        return platformData.getIslamicFinanceData().stream()
            .map(this::transformIslamicProduct)
            .toList();
    }

    private List<Loan> transformExpenseCreditProducts(PlatformData platformData) {
        // Transform Masrufi expense-linked credit products
        return platformData.getExpenseCreditData().stream()
            .map(this::transformExpenseCreditProduct)
            .toList();
    }

    private Loan transformEnterpriseLoan(Object loanData) {
        // Implementation for enterprise loan transformation
        return Loan.builder()
            .loanId("ENT-" + UUID.randomUUID().toString().substring(0, 8))
            .loanType(LoanType.TRADITIONAL)
            .productName("Enterprise Loan")
            .currency("AED")
            .status(LoanStatus.ACTIVE)
            .build();
    }

    private Loan transformIslamicProduct(Object productData) {
        // Implementation for Islamic finance product transformation
        return Loan.builder()
            .loanId("ISL-" + UUID.randomUUID().toString().substring(0, 8))
            .loanType(LoanType.ISLAMIC_FINANCE)
            .productName("Islamic Finance Product")
            .currency("AED")
            .status(LoanStatus.ACTIVE)
            .shariaCompliant(true)
            .islamicFinanceType("MURABAHA") // Example
            .build();
    }

    private Loan transformExpenseCreditProduct(Object productData) {
        // Implementation for expense credit product transformation
        return Loan.builder()
            .loanId("EXP-" + UUID.randomUUID().toString().substring(0, 8))
            .loanType(LoanType.EXPENSE_CREDIT)
            .productName("Expense-Linked Credit")
            .currency("AED")
            .status(LoanStatus.ACTIVE)
            .linkedToExpenses(true)
            .build();
    }

    // Additional helper methods for calculations
    private Money calculateTotalOutstanding(List<Loan> loans) {
        return loans.stream()
            .map(loan -> loan.getOutstandingBalance())
            .reduce(Money.ZERO, Money::add);
    }

    private Money calculateTotalMonthlyPayment(List<Loan> loans) {
        return loans.stream()
            .map(loan -> loan.getMonthlyPayment())
            .reduce(Money.ZERO, Money::add);
    }

    private PlatformLoanSummary extractLoanSummaryFromPlatform(PlatformData platformData) {
        // Extract loan summary from platform-specific data
        return PlatformLoanSummary.builder()
            .totalOutstanding(Money.of(150000, "AED"))
            .monthlyPayment(Money.of(5000, "AED"))
            .build();
    }

    private double calculateCreditUtilization(AggregatedData aggregatedData) {
        // Calculate credit utilization across all platforms
        return 65.5; // Example percentage
    }

    private double calculateAverageRate(AggregatedData aggregatedData) {
        // Calculate average interest/profit rate
        return 8.75; // Example rate
    }

    private double calculateShariaCompliantPercentage(Money islamicFinance, Money totalFinance) {
        if (totalFinance.isZero()) return 0.0;
        return islamicFinance.divide(totalFinance).multiply(100.0).doubleValue();
    }
}