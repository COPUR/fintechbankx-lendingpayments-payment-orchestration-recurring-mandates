package com.enterprise.openfinance.infrastructure.monitoring;

import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Real-time alerting service for Open Finance compliance and security monitoring.
 * 
 * Provides immediate notifications for:
 * - Security violations and threats
 * - Compliance breaches and regulatory violations
 * - System performance anomalies
 * - Data sharing failures and timeouts
 * - Participant authentication issues
 * 
 * Supports multiple notification channels: email, Slack, SMS, webhook
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertingService {

    private final EmailNotificationService emailService;
    private final SlackNotificationService slackService;
    private final WebhookNotificationService webhookService;
    private final SMSNotificationService smsService;
    private final AlertConfigurationService alertConfigService;

    // === Security Alerts ===

    @Async
    public CompletableFuture<Void> sendSecurityAlert(String alertTitle, String participantId, 
                                                    String severity, String description) {
        log.warn("🚨 Security alert triggered - Title: {}, Participant: {}, Severity: {}", 
            alertTitle, participantId, severity);

        var alert = SecurityAlert.builder()
            .alertId("SEC-" + System.currentTimeMillis())
            .title(alertTitle)
            .participantId(participantId)
            .severity(AlertSeverity.valueOf(severity))
            .description(description)
            .timestamp(Instant.now())
            .alertType(AlertType.SECURITY_VIOLATION)
            .status(AlertStatus.ACTIVE)
            .build();

        return CompletableFuture.runAsync(() -> {
            try {
                // Get alert configuration for security alerts
                var alertConfig = alertConfigService.getSecurityAlertConfiguration();
                
                // Send to appropriate channels based on severity
                if (alert.getSeverity() == AlertSeverity.CRITICAL) {
                    // Critical alerts go to all channels
                    sendToAllChannels(alert, alertConfig);
                    
                    // For critical alerts, also send SMS to on-call team
                    smsService.sendCriticalAlert(alert, alertConfig.getOnCallNumbers());
                    
                } else if (alert.getSeverity() == AlertSeverity.HIGH) {
                    // High severity alerts go to email and Slack
                    emailService.sendSecurityAlert(alert, alertConfig.getSecurityTeamEmails());
                    slackService.sendSecurityAlert(alert, alertConfig.getSecuritySlackChannel());
                    
                } else {
                    // Medium/Low alerts go to Slack only
                    slackService.sendSecurityAlert(alert, alertConfig.getSecuritySlackChannel());
                }
                
                // Send webhook notification for external security systems
                webhookService.sendSecurityAlert(alert, alertConfig.getSecurityWebhooks());
                
                log.info("✅ Security alert sent successfully - AlertId: {}", alert.getAlertId());
                
            } catch (Exception e) {
                log.error("💥 Failed to send security alert - AlertId: {}", alert.getAlertId(), e);
            }
        });
    }

    @Async
    public CompletableFuture<Void> sendFAPISecurityViolation(String violationType, 
                                                            ParticipantId participantId, 
                                                            String endpoint, String details) {
        var alertDescription = String.format(
            "FAPI 2.0 security violation detected.\n" +
            "Violation Type: %s\n" +
            "Participant: %s\n" +
            "Endpoint: %s\n" +
            "Details: %s\n" +
            "Immediate investigation required.",
            violationType, participantId.getValue(), endpoint, details
        );

        return sendSecurityAlert(
            "FAPI 2.0 Security Violation", 
            participantId.getValue(),
            "HIGH", 
            alertDescription
        );
    }

    // === Compliance Alerts ===

    @Async
    public CompletableFuture<Void> sendComplianceAlert(String alertTitle, String participantId, 
                                                      String complianceStatus, String details) {
        log.warn("⚖️ Compliance alert triggered - Title: {}, Participant: {}, Status: {}", 
            alertTitle, participantId, complianceStatus);

        var alert = ComplianceAlert.builder()
            .alertId("COMP-" + System.currentTimeMillis())
            .title(alertTitle)
            .participantId(participantId)
            .complianceStatus(ComplianceStatus.valueOf(complianceStatus))
            .description(details)
            .timestamp(Instant.now())
            .alertType(AlertType.COMPLIANCE_VIOLATION)
            .regulationType(RegulationType.CBUAE_C7_2023)
            .status(AlertStatus.ACTIVE)
            .build();

        return CompletableFuture.runAsync(() -> {
            try {
                var alertConfig = alertConfigService.getComplianceAlertConfiguration();
                
                // Compliance alerts are always high priority
                emailService.sendComplianceAlert(alert, alertConfig.getComplianceTeamEmails());
                slackService.sendComplianceAlert(alert, alertConfig.getComplianceSlackChannel());
                
                // Send to regulatory reporting webhook
                webhookService.sendComplianceAlert(alert, alertConfig.getRegulatoryWebhooks());
                
                // For major non-compliance, escalate to management
                if (alert.getComplianceStatus() == ComplianceStatus.NON_COMPLIANT_MAJOR) {
                    emailService.sendComplianceEscalation(alert, alertConfig.getManagementEmails());
                    smsService.sendComplianceAlert(alert, alertConfig.getManagementPhones());
                }
                
                log.info("✅ Compliance alert sent successfully - AlertId: {}", alert.getAlertId());
                
            } catch (Exception e) {
                log.error("💥 Failed to send compliance alert - AlertId: {}", alert.getAlertId(), e);
            }
        });
    }

    @Async
    public CompletableFuture<Void> sendPCIComplianceViolation(String violationType, 
                                                             String participantId, 
                                                             double complianceScore) {
        var alertDescription = String.format(
            "PCI-DSS v4 compliance violation detected.\n" +
            "Violation Type: %s\n" +
            "Participant: %s\n" +
            "Compliance Score: %.2f%%\n" +
            "Immediate remediation required to maintain PCI compliance status.",
            violationType, participantId, complianceScore
        );

        return sendComplianceAlert(
            "PCI-DSS v4 Compliance Violation",
            participantId,
            complianceScore >= 90.0 ? "NON_COMPLIANT_MINOR" : "NON_COMPLIANT_MAJOR",
            alertDescription
        );
    }

    // === Performance Alerts ===

    @Async
    public CompletableFuture<Void> sendPerformanceAlert(String alertTitle, String component,
                                                       String metric, String threshold, 
                                                       String currentValue) {
        log.warn("📊 Performance alert triggered - Component: {}, Metric: {}, Current: {}, Threshold: {}", 
            component, metric, currentValue, threshold);

        var alert = PerformanceAlert.builder()
            .alertId("PERF-" + System.currentTimeMillis())
            .title(alertTitle)
            .component(component)
            .metric(metric)
            .threshold(threshold)
            .currentValue(currentValue)
            .timestamp(Instant.now())
            .alertType(AlertType.PERFORMANCE_DEGRADATION)
            .severity(calculatePerformanceSeverity(metric, threshold, currentValue))
            .status(AlertStatus.ACTIVE)
            .build();

        return CompletableFuture.runAsync(() -> {
            try {
                var alertConfig = alertConfigService.getPerformanceAlertConfiguration();
                
                // Performance alerts go to operations team
                slackService.sendPerformanceAlert(alert, alertConfig.getOperationsSlackChannel());
                
                if (alert.getSeverity() == AlertSeverity.HIGH || alert.getSeverity() == AlertSeverity.CRITICAL) {
                    emailService.sendPerformanceAlert(alert, alertConfig.getOperationsTeamEmails());
                }
                
                // Send to monitoring webhooks
                webhookService.sendPerformanceAlert(alert, alertConfig.getMonitoringWebhooks());
                
                log.info("✅ Performance alert sent successfully - AlertId: {}", alert.getAlertId());
                
            } catch (Exception e) {
                log.error("💥 Failed to send performance alert - AlertId: {}", alert.getAlertId(), e);
            }
        });
    }

    // === Data Sharing Alerts ===

    @Async
    public CompletableFuture<Void> sendDataSharingFailureAlert(String sagaId, String[] platforms, 
                                                              String failureReason, ParticipantId participantId) {
        var alertDescription = String.format(
            "Cross-platform data sharing failure detected.\n" +
            "Saga ID: %s\n" +
            "Participant: %s\n" +
            "Affected Platforms: %s\n" +
            "Failure Reason: %s\n" +
            "Manual intervention may be required.",
            sagaId, participantId.getValue(), String.join(", ", platforms), failureReason
        );

        return sendPerformanceAlert(
            "Data Sharing Failure",
            "DataSharingSaga",
            "saga_completion_rate",
            "95%",
            "Failed"
        );
    }

    // === Report Distribution ===

    @Async
    public CompletableFuture<Void> sendComplianceReport(ComplianceReport report) {
        log.info("📊 Distributing compliance report - ReportId: {}, Type: {}", 
            report.getReportId(), report.getReportType());

        return CompletableFuture.runAsync(() -> {
            try {
                var alertConfig = alertConfigService.getReportDistributionConfiguration();
                
                // Send daily reports to compliance team
                if (report.getReportType() == ComplianceReportType.DAILY_SUMMARY) {
                    emailService.sendDailyComplianceReport(report, alertConfig.getComplianceTeamEmails());
                }
                
                // Send regulatory reports to external systems
                if (report.getReportType() == ComplianceReportType.REGULATORY_FILING) {
                    webhookService.sendRegulatoryReport(report, alertConfig.getRegulatoryWebhooks());
                }
                
                // Send executive summary to management
                if (report.hasSignificantViolations()) {
                    emailService.sendExecutiveComplianceSummary(report, alertConfig.getExecutiveEmails());
                }
                
                log.info("✅ Compliance report distributed successfully - ReportId: {}", report.getReportId());
                
            } catch (Exception e) {
                log.error("💥 Failed to distribute compliance report - ReportId: {}", report.getReportId(), e);
            }
        });
    }

    // === Alert Management ===

    @Async
    public CompletableFuture<Void> acknowledgeAlert(String alertId, String acknowledgedBy, String notes) {
        log.info("✅ Alert acknowledged - AlertId: {}, By: {}", alertId, acknowledgedBy);
        
        return CompletableFuture.runAsync(() -> {
            try {
                // Update alert status to acknowledged
                var acknowledgment = AlertAcknowledgment.builder()
                    .alertId(alertId)
                    .acknowledgedBy(acknowledgedBy)
                    .acknowledgedAt(Instant.now())
                    .notes(notes)
                    .build();
                
                // Store acknowledgment
                alertConfigService.recordAlertAcknowledgment(acknowledgment);
                
                // Notify team of acknowledgment
                var alertConfig = alertConfigService.getGeneralAlertConfiguration();
                slackService.sendAlertAcknowledgment(acknowledgment, alertConfig.getGeneralSlackChannel());
                
                log.info("✅ Alert acknowledgment processed - AlertId: {}", alertId);
                
            } catch (Exception e) {
                log.error("💥 Failed to process alert acknowledgment - AlertId: {}", alertId, e);
            }
        });
    }

    @Async
    public CompletableFuture<Void> resolveAlert(String alertId, String resolvedBy, String resolution) {
        log.info("🔄 Alert resolved - AlertId: {}, By: {}", alertId, resolvedBy);
        
        return CompletableFuture.runAsync(() -> {
            try {
                var resolution_record = AlertResolution.builder()
                    .alertId(alertId)
                    .resolvedBy(resolvedBy)
                    .resolvedAt(Instant.now())
                    .resolution(resolution)
                    .build();
                
                // Store resolution
                alertConfigService.recordAlertResolution(resolution_record);
                
                // Notify team of resolution
                var alertConfig = alertConfigService.getGeneralAlertConfiguration();
                slackService.sendAlertResolution(resolution_record, alertConfig.getGeneralSlackChannel());
                
                log.info("✅ Alert resolution processed - AlertId: {}", alertId);
                
            } catch (Exception e) {
                log.error("💥 Failed to process alert resolution - AlertId: {}", alertId, e);
            }
        });
    }

    // === Escalation Management ===

    @Async
    public CompletableFuture<Void> escalateAlert(String alertId, String reason) {
        log.warn("🚨 Alert escalated - AlertId: {}, Reason: {}", alertId, reason);
        
        return CompletableFuture.runAsync(() -> {
            try {
                var escalation = AlertEscalation.builder()
                    .alertId(alertId)
                    .escalatedAt(Instant.now())
                    .escalationReason(reason)
                    .escalationLevel(AlertEscalationLevel.LEVEL_2)
                    .build();
                
                // Store escalation
                alertConfigService.recordAlertEscalation(escalation);
                
                // Send escalation notifications
                var alertConfig = alertConfigService.getEscalationConfiguration();
                emailService.sendAlertEscalation(escalation, alertConfig.getLevel2Emails());
                smsService.sendAlertEscalation(escalation, alertConfig.getLevel2Phones());
                
                log.info("✅ Alert escalation processed - AlertId: {}", alertId);
                
            } catch (Exception e) {
                log.error("💥 Failed to process alert escalation - AlertId: {}", alertId, e);
            }
        });
    }

    // === Helper Methods ===

    private void sendToAllChannels(SecurityAlert alert, AlertConfiguration config) {
        // Send to all available channels for critical alerts
        emailService.sendSecurityAlert(alert, config.getSecurityTeamEmails());
        slackService.sendSecurityAlert(alert, config.getSecuritySlackChannel());
        webhookService.sendSecurityAlert(alert, config.getSecurityWebhooks());
    }

    private AlertSeverity calculatePerformanceSeverity(String metric, String threshold, String currentValue) {
        // Simple logic to determine severity based on metric deviation
        try {
            var thresholdNum = Double.parseDouble(threshold.replaceAll("[^0-9.]", ""));
            var currentNum = Double.parseDouble(currentValue.replaceAll("[^0-9.]", ""));
            
            var deviationPercent = Math.abs(currentNum - thresholdNum) / thresholdNum * 100;
            
            if (deviationPercent > 50) return AlertSeverity.CRITICAL;
            if (deviationPercent > 30) return AlertSeverity.HIGH;
            if (deviationPercent > 15) return AlertSeverity.MEDIUM;
            return AlertSeverity.LOW;
            
        } catch (Exception e) {
            return AlertSeverity.MEDIUM; // Default severity
        }
    }
}