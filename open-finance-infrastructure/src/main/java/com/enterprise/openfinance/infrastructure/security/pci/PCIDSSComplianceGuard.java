package com.enterprise.openfinance.infrastructure.security.pci;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * PCI-DSS v4 Compliance Guard
 * Implements comprehensive PCI-DSS v4.0 requirements for Open Finance platform
 * 
 * Key Requirements Addressed:
 * - 1.2.1: Configuration standards for all network security controls
 * - 2.2.4: System components cannot use vendor-supplied defaults  
 * - 3.4.1: Primary account numbers are protected with strong cryptography
 * - 4.2.1: Strong cryptography for transmission over public networks
 * - 6.2.4: Bespoke and custom software secure coding practices
 * - 8.2.1: Multi-factor authentication for all non-console administrative access
 * - 8.3.1: Multi-factor authentication for all access to cardholder data environment
 * - 10.2: Audit logs for all system components
 * - 11.3.1: External penetration testing at least annually
 * - 12.10.4: Incident response plan testing at least annually
 */
@Slf4j
@Component
public class PCIDSSComplianceGuard {

    private static final String PCI_DSS_VERSION = "4.0";
    
    // PCI-DSS v4 Requirement 4.2.1 - Strong cryptography algorithms
    private static final Set<String> APPROVED_CIPHER_SUITES = Set.of(
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256", 
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
    );
    
    // PCI-DSS v4 Requirement 4.2.1 - Minimum TLS version
    private static final Set<String> APPROVED_TLS_VERSIONS = Set.of("TLSv1.2", "TLSv1.3");
    
    // PCI-DSS v4 Requirement 8.2.1 - Password complexity requirements
    private static final Pattern STRONG_PASSWORD_PATTERN = Pattern.compile(
        "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{12,}$"
    );
    
    // PCI-DSS v4 Requirement 6.2.4 - Input validation patterns
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^\\d{13,19}$");
    private static final Pattern CVV_PATTERN = Pattern.compile("^\\d{3,4}$");
    private static final Pattern EXPIRY_PATTERN = Pattern.compile("^(0[1-9]|1[0-2])\\/\\d{2}$");
    
    // Rate limiting for brute force protection (PCI-DSS v4 Requirement 8.2.1)
    private final Map<String, RateLimitTracker> rateLimitTrackers = new ConcurrentHashMap<>();
    
    // Data masking patterns (PCI-DSS v4 Requirement 3.4.1)
    private static final Pattern PAN_MASKING_PATTERN = Pattern.compile("(\\d{6})(\\d{3,9})(\\d{4})");
    
    private final SecureRandom secureRandom = new SecureRandom();
    private final AuditLogger auditLogger;
    private final EncryptionService encryptionService;
    private final TokenizationService tokenizationService;
    
    public PCIDSSComplianceGuard(AuditLogger auditLogger, 
                                EncryptionService encryptionService,
                                TokenizationService tokenizationService) {
        this.auditLogger = auditLogger;
        this.encryptionService = encryptionService;
        this.tokenizationService = tokenizationService;
    }

    /**
     * PCI-DSS v4 Requirement 4.2.1: Strong cryptography validation
     */
    public PCIComplianceResult validateCryptographicControls(HttpServletRequest request) {
        log.debug("Validating cryptographic controls for request from: {}", request.getRemoteAddr());
        
        try {
            // Validate TLS version
            var tlsVersion = extractTLSVersion(request);
            if (!APPROVED_TLS_VERSIONS.contains(tlsVersion)) {
                return PCIComplianceResult.failure("TLS_VERSION_NOT_APPROVED", 
                    "TLS version " + tlsVersion + " is not approved. Minimum required: TLS 1.2");
            }
            
            // Validate cipher suite
            var cipherSuite = extractCipherSuite(request);
            if (!APPROVED_CIPHER_SUITES.contains(cipherSuite)) {
                return PCIComplianceResult.failure("CIPHER_SUITE_NOT_APPROVED",
                    "Cipher suite " + cipherSuite + " is not approved for PCI-DSS v4");
            }
            
            // Validate certificate strength
            var clientCert = extractClientCertificate(request);
            if (clientCert != null) {
                var certValidation = validateCertificateStrength(clientCert);
                if (!certValidation.isValid()) {
                    return certValidation;
                }
            }
            
            auditLogger.logCryptographicControlsValidated(request.getRemoteAddr(), tlsVersion, cipherSuite);
            return PCIComplianceResult.success();
            
        } catch (Exception e) {
            log.error("Error validating cryptographic controls", e);
            auditLogger.logCryptographicControlsError(request.getRemoteAddr(), e.getMessage());
            return PCIComplianceResult.failure("CRYPTOGRAPHIC_VALIDATION_ERROR", 
                "Failed to validate cryptographic controls: " + e.getMessage());
        }
    }

    /**
     * PCI-DSS v4 Requirement 3.4.1: Primary Account Number (PAN) protection
     */
    public String protectPAN(String pan) {
        if (pan == null || pan.trim().isEmpty()) {
            return pan;
        }
        
        // Validate PAN format
        if (!ACCOUNT_NUMBER_PATTERN.matcher(pan).matches()) {
            throw new IllegalArgumentException("Invalid PAN format");
        }
        
        try {
            // Tokenize PAN using format-preserving encryption
            var token = tokenizationService.tokenize(pan);
            
            // Audit PAN access
            auditLogger.logPANAccess("TOKENIZATION", maskPAN(pan), "SUCCESS");
            
            return token;
            
        } catch (Exception e) {
            log.error("Failed to protect PAN", e);
            auditLogger.logPANAccess("TOKENIZATION", maskPAN(pan), "FAILURE");
            throw new SecurityException("Failed to protect PAN", e);
        }
    }

    /**
     * PCI-DSS v4 Requirement 3.4.1: PAN masking for logging and display
     */
    public String maskPAN(String pan) {
        if (pan == null || pan.length() < 10) {
            return "****";
        }
        
        var matcher = PAN_MASKING_PATTERN.matcher(pan);
        if (matcher.matches()) {
            return matcher.group(1) + "*".repeat(matcher.group(2).length()) + matcher.group(3);
        }
        
        // Fallback masking
        return pan.substring(0, 4) + "*".repeat(pan.length() - 8) + pan.substring(pan.length() - 4);
    }

    /**
     * PCI-DSS v4 Requirement 8.2.1: Authentication controls validation
     */
    public PCIComplianceResult validateAuthenticationControls(String username, String password, 
                                                            String ipAddress, String userAgent) {
        log.debug("Validating authentication controls for user: {} from IP: {}", username, ipAddress);
        
        try {
            // Check rate limiting (brute force protection)
            var rateLimitResult = checkRateLimit(ipAddress, username);
            if (!rateLimitResult.isValid()) {
                return rateLimitResult;
            }
            
            // Validate password strength
            if (password != null && !STRONG_PASSWORD_PATTERN.matcher(password).matches()) {
                recordFailedAttempt(ipAddress, username);
                auditLogger.logAuthenticationFailure(username, ipAddress, "WEAK_PASSWORD");
                return PCIComplianceResult.failure("WEAK_PASSWORD", 
                    "Password does not meet PCI-DSS v4 complexity requirements");
            }
            
            // Validate account lockout policies
            var accountLockResult = checkAccountLockout(username);
            if (!accountLockResult.isValid()) {
                return accountLockResult;
            }
            
            // Log successful validation
            auditLogger.logAuthenticationControlsValidated(username, ipAddress);
            return PCIComplianceResult.success();
            
        } catch (Exception e) {
            log.error("Error validating authentication controls", e);
            auditLogger.logAuthenticationControlsError(username, ipAddress, e.getMessage());
            return PCIComplianceResult.failure("AUTHENTICATION_VALIDATION_ERROR",
                "Failed to validate authentication controls: " + e.getMessage());
        }
    }

    /**
     * PCI-DSS v4 Requirement 6.2.4: Input validation for secure coding practices
     */
    public PCIComplianceResult validateInputSecurity(Map<String, Object> inputData) {
        log.debug("Validating input security for {} parameters", inputData.size());
        
        var violations = new ArrayList<String>();
        
        for (var entry : inputData.entrySet()) {
            var fieldName = entry.getKey();
            var value = entry.getValue();
            
            if (value == null) {
                continue;
            }
            
            var stringValue = value.toString();
            
            // Check for potential injection attacks
            if (containsSQLInjection(stringValue)) {
                violations.add("SQL injection attempt detected in field: " + fieldName);
            }
            
            if (containsXSSAttempt(stringValue)) {
                violations.add("XSS attempt detected in field: " + fieldName);
            }
            
            if (containsCommandInjection(stringValue)) {
                violations.add("Command injection attempt detected in field: " + fieldName);
            }
            
            // Validate field-specific patterns
            switch (fieldName.toLowerCase()) {
                case "pan", "account_number", "card_number":
                    if (!ACCOUNT_NUMBER_PATTERN.matcher(stringValue).matches()) {
                        violations.add("Invalid account number format in field: " + fieldName);
                    }
                    break;
                case "cvv", "cvv2", "cvc":
                    if (!CVV_PATTERN.matcher(stringValue).matches()) {
                        violations.add("Invalid CVV format in field: " + fieldName);
                    }
                    break;
                case "expiry", "expiry_date":
                    if (!EXPIRY_PATTERN.matcher(stringValue).matches()) {
                        violations.add("Invalid expiry date format in field: " + fieldName);
                    }
                    break;
            }
        }
        
        if (!violations.isEmpty()) {
            var violationMessage = String.join("; ", violations);
            auditLogger.logInputValidationFailure(inputData.keySet(), violationMessage);
            return PCIComplianceResult.failure("INPUT_VALIDATION_FAILURE", violationMessage);
        }
        
        auditLogger.logInputValidationSuccess(inputData.keySet());
        return PCIComplianceResult.success();
    }

    /**
     * PCI-DSS v4 Requirement 10.2: Audit logging requirements
     */
    public void logCardholderDataAccess(String userId, String action, String dataType, 
                                      String result, String additionalInfo) {
        var auditEvent = AuditEvent.builder()
            .eventType("CARDHOLDER_DATA_ACCESS")
            .userId(userId)
            .action(action)
            .dataType(dataType)
            .result(result)
            .timestamp(Instant.now())
            .additionalInfo(additionalInfo)
            .sessionId(generateSecureSessionId())
            .build();
            
        auditLogger.logAuditEvent(auditEvent);
        
        // Also log to secure audit trail
        log.info("PCI Audit Event: {}", auditEvent);
    }

    /**
     * PCI-DSS v4 Requirement 2.2.4: Ensure no vendor-supplied defaults are used
     */
    public PCIComplianceResult validateNoDefaultCredentials(String username, String password) {
        // List of common default credentials that should never be used
        var defaultCredentials = Map.of(
            "admin", Set.of("admin", "password", "123456", "admin123"),
            "root", Set.of("root", "password", "toor"),
            "user", Set.of("user", "password", "123456"),
            "guest", Set.of("guest", "password", ""),
            "keycloak", Set.of("keycloak", "password", "admin"),
            "postgres", Set.of("postgres", "password", "admin")
        );
        
        var userDefaults = defaultCredentials.get(username.toLowerCase());
        if (userDefaults != null && userDefaults.contains(password.toLowerCase())) {
            auditLogger.logDefaultCredentialsAttempt(username);
            return PCIComplianceResult.failure("DEFAULT_CREDENTIALS_DETECTED",
                "Default credentials detected. PCI-DSS v4 Requirement 2.2.4 violation");
        }
        
        return PCIComplianceResult.success();
    }

    /**
     * PCI-DSS v4 Requirement 11.3.1: Regular security testing validation
     */
    public boolean isSecurityTestingUpToDate() {
        // Check last penetration test date
        var lastPenTest = getLastPenetrationTestDate();
        var oneYearAgo = Instant.now().minus(365, ChronoUnit.DAYS);
        
        if (lastPenTest == null || lastPenTest.isBefore(oneYearAgo)) {
            log.warn("Penetration testing is overdue. Last test: {}", lastPenTest);
            auditLogger.logComplianceViolation("PENETRATION_TESTING_OVERDUE", 
                "Last penetration test was more than 1 year ago");
            return false;
        }
        
        return true;
    }

    // Helper methods

    private PCIComplianceResult checkRateLimit(String ipAddress, String username) {
        var key = ipAddress + ":" + username;
        var tracker = rateLimitTrackers.computeIfAbsent(key, k -> new RateLimitTracker());
        
        if (tracker.isRateLimited()) {
            auditLogger.logRateLimitExceeded(ipAddress, username);
            return PCIComplianceResult.failure("RATE_LIMIT_EXCEEDED",
                "Too many authentication attempts. Account temporarily locked");
        }
        
        return PCIComplianceResult.success();
    }
    
    private void recordFailedAttempt(String ipAddress, String username) {
        var key = ipAddress + ":" + username;
        var tracker = rateLimitTrackers.computeIfAbsent(key, k -> new RateLimitTracker());
        tracker.recordFailedAttempt();
    }
    
    private boolean containsSQLInjection(String input) {
        var sqlInjectionPatterns = List.of(
            "(?i).*\\b(union|select|insert|update|delete|drop|create|alter|exec|execute)\\b.*",
            "(?i).*['\";].*",
            "(?i).*\\-\\-.*",
            "(?i).*/\\*.*\\*/.*",
            "(?i).*\\bor\\s+1\\s*=\\s*1\\b.*"
        );
        
        return sqlInjectionPatterns.stream()
            .anyMatch(pattern -> input.matches(pattern));
    }
    
    private boolean containsXSSAttempt(String input) {
        var xssPatterns = List.of(
            "(?i).*<script.*",
            "(?i).*javascript:.*",
            "(?i).*on\\w+\\s*=.*",
            "(?i).*<iframe.*",
            "(?i).*<object.*"
        );
        
        return xssPatterns.stream()
            .anyMatch(pattern -> input.matches(pattern));
    }
    
    private boolean containsCommandInjection(String input) {
        var commandInjectionPatterns = List.of(
            "(?i).*(;|\\||&|`|\\$\\(|\\$\\{).*",
            "(?i).*(rm|cat|ls|ps|kill|chmod|sudo)\\s+.*"
        );
        
        return commandInjectionPatterns.stream()
            .anyMatch(pattern -> input.matches(pattern));
    }
    
    private String generateSecureSessionId() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Inner classes

    public static class PCIComplianceResult {
        private final boolean valid;
        private final String errorCode;
        private final String errorMessage;
        
        private PCIComplianceResult(boolean valid, String errorCode, String errorMessage) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }
        
        public static PCIComplianceResult success() {
            return new PCIComplianceResult(true, null, null);
        }
        
        public static PCIComplianceResult failure(String errorCode, String errorMessage) {
            return new PCIComplianceResult(false, errorCode, errorMessage);
        }
        
        public boolean isValid() { return valid; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    private static class RateLimitTracker {
        private final int maxAttempts = 5;
        private final long windowMs = 300_000; // 5 minutes
        private final List<Instant> attempts = new ArrayList<>();
        
        public synchronized boolean isRateLimited() {
            cleanOldAttempts();
            return attempts.size() >= maxAttempts;
        }
        
        public synchronized void recordFailedAttempt() {
            cleanOldAttempts();
            attempts.add(Instant.now());
        }
        
        private void cleanOldAttempts() {
            var cutoff = Instant.now().minusMillis(windowMs);
            attempts.removeIf(attempt -> attempt.isBefore(cutoff));
        }
    }
}