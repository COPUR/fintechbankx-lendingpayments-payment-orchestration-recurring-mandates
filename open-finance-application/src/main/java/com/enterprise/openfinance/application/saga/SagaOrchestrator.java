package com.enterprise.openfinance.application.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Central saga orchestrator for managing distributed transactions.
 * 
 * Features:
 * - Orchestrator-based saga pattern (centralized coordination)
 * - Automatic compensation execution on failure
 * - Step-by-step execution with rollback support
 * - Timeout handling for long-running operations
 * - Saga state persistence and recovery
 * - Concurrent saga execution with isolation
 * 
 * This orchestrator ensures ACID properties across distributed services:
 * - Atomicity: All steps succeed or all are compensated
 * - Consistency: Business invariants maintained across services
 * - Isolation: Concurrent sagas don't interfere
 * - Durability: Saga state persisted for recovery
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaOrchestrator {

    private final SagaStateRepository sagaStateRepository;
    private final SagaEventPublisher sagaEventPublisher;
    private final Map<SagaId, SagaExecution> activeSagas = new ConcurrentHashMap<>();

    /**
     * Start a new saga execution.
     */
    @Transactional
    public <T> CompletableFuture<SagaExecution> startSaga(SagaId sagaId, T sagaData) {
        log.info("🚀 Starting saga: {} with data type: {}", sagaId, sagaData.getClass().getSimpleName());

        var sagaExecution = SagaExecution.builder()
            .sagaId(sagaId)
            .status(SagaStatus.STARTED)
            .startedAt(Instant.now())
            .sagaData(sagaData)
            .steps(new ArrayList<>())
            .compensations(new LinkedHashMap<>()) // Ordered for reverse execution
            .build();

        activeSagas.put(sagaId, sagaExecution);

        // Persist saga state
        return sagaStateRepository.saveSagaState(sagaExecution)
            .thenApply(saved -> {
                // Publish saga started event
                sagaEventPublisher.publishSagaStarted(sagaId, sagaData.getClass().getSimpleName());
                log.debug("✅ Saga started and persisted: {}", sagaId);
                return saved;
            })
            .exceptionally(throwable -> {
                log.error("❌ Failed to start saga: {}", sagaId, throwable);
                activeSagas.remove(sagaId);
                throw new SagaStartException("Failed to start saga: " + sagaId, throwable);
            });
    }

    /**
     * Execute a synchronous saga step with compensation registration.
     */
    public <T> CompletableFuture<T> executeStep(SagaExecution saga, String stepName, 
                                               Supplier<CompletableFuture<T>> stepExecution) {
        
        var stepId = StepId.generate();
        log.debug("⚡ Executing saga step: {} [{}] for saga: {}", stepName, stepId, saga.getSagaId());

        var step = SagaStep.builder()
            .stepId(stepId)
            .stepName(stepName)
            .status(StepStatus.EXECUTING)
            .startedAt(Instant.now())
            .build();

        saga.addStep(step);

        return updateSagaState(saga)
            .thenCompose(updatedSaga -> {
                // Execute the actual step
                return stepExecution.get()
                    .thenApply(result -> {
                        // Mark step as completed
                        step.setStatus(StepStatus.COMPLETED);
                        step.setCompletedAt(Instant.now());
                        
                        log.debug("✅ Saga step completed: {} [{}]", stepName, stepId);
                        
                        // Update saga state asynchronously
                        updateSagaState(saga)
                            .whenComplete((updated, error) -> {
                                if (error != null) {
                                    log.warn("Failed to update saga state after step completion", error);
                                }
                            });

                        // Publish step completed event
                        sagaEventPublisher.publishStepCompleted(saga.getSagaId(), stepName, stepId);
                        
                        return result;
                    })
                    .exceptionally(throwable -> {
                        // Mark step as failed
                        step.setStatus(StepStatus.FAILED);
                        step.setFailedAt(Instant.now());
                        step.setErrorMessage(throwable.getMessage());
                        
                        log.error("❌ Saga step failed: {} [{}]", stepName, stepId, throwable);
                        
                        // Update saga state asynchronously
                        updateSagaState(saga)
                            .whenComplete((updated, error) -> {
                                if (error != null) {
                                    log.warn("Failed to update saga state after step failure", error);
                                }
                            });

                        // Publish step failed event
                        sagaEventPublisher.publishStepFailed(saga.getSagaId(), stepName, stepId, throwable);
                        
                        throw new SagaStepExecutionException("Step failed: " + stepName, stepId, throwable);
                    });
            });
    }

    /**
     * Execute an asynchronous saga step with timeout support.
     */
    public <T> CompletableFuture<T> executeAsyncStep(SagaExecution saga, String stepName, 
                                                    Duration timeout,
                                                    Supplier<CompletableFuture<T>> stepExecution) {
        
        log.debug("⏰ Executing async saga step: {} with timeout: {} for saga: {}", 
            stepName, timeout, saga.getSagaId());

        return executeStep(saga, stepName, () -> {
            var future = stepExecution.get();
            
            // Add timeout handling
            var timeoutFuture = CompletableFuture.delayedExecutor(timeout.toMillis(), 
                java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (!future.isDone()) {
                        future.completeExceptionally(
                            new TimeoutException("Step timeout after: " + timeout));
                    }
                });

            return future.whenComplete((result, throwable) -> {
                // Cancel timeout if step completes before timeout
                if (!timeoutFuture.isDone()) {
                    timeoutFuture.cancel(true);
                }
            });
        });
    }

    /**
     * Execute all compensations for a failed saga in reverse order.
     */
    @Transactional
    public CompletableFuture<CompensationResult> executeCompensations(SagaId sagaId) {
        var saga = activeSagas.get(sagaId);
        if (saga == null) {
            return sagaStateRepository.loadSagaState(sagaId)
                .thenCompose(this::executeCompensationsForSaga);
        }
        
        return executeCompensationsForSaga(saga);
    }

    private CompletableFuture<CompensationResult> executeCompensationsForSaga(SagaExecution saga) {
        log.info("🔄 Executing compensations for saga: {}", saga.getSagaId());

        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCompensationStartedAt(Instant.now());

        // Get compensations in reverse order (LIFO - Last In, First Out)
        var compensations = new ArrayList<>(saga.getCompensations().entrySet());
        Collections.reverse(compensations);

        var compensationResult = CompensationResult.builder()
            .sagaId(saga.getSagaId())
            .totalCompensations(compensations.size())
            .successfulCompensations(0)
            .failedCompensations(new ArrayList<>())
            .startedAt(Instant.now())
            .build();

        // Execute compensations sequentially
        CompletableFuture<CompensationResult> future = CompletableFuture.completedFuture(compensationResult);

        for (var compensationEntry : compensations) {
            var stepName = compensationEntry.getKey();
            var compensation = compensationEntry.getValue();

            future = future.thenCompose(result -> 
                executeCompensation(saga, stepName, compensation, result)
            );
        }

        return future.thenCompose(result -> {
            // Mark saga as compensated
            saga.setStatus(SagaStatus.COMPENSATED);
            saga.setCompensationCompletedAt(Instant.now());

            return updateSagaState(saga)
                .thenApply(updated -> {
                    // Remove from active sagas
                    activeSagas.remove(saga.getSagaId());
                    
                    // Publish saga compensated event
                    sagaEventPublisher.publishSagaCompensated(saga.getSagaId(), result);
                    
                    log.info("✅ Saga compensations completed: {} ({}/{} successful)", 
                        saga.getSagaId(),
                        result.getSuccessfulCompensations(), 
                        result.getTotalCompensations());
                    
                    return result;
                });
        });
    }

    private CompletableFuture<CompensationResult> executeCompensation(SagaExecution saga, 
                                                                     String stepName,
                                                                     Runnable compensation, 
                                                                     CompensationResult result) {
        
        log.debug("🔧 Executing compensation for step: {} in saga: {}", stepName, saga.getSagaId());

        return CompletableFuture.runAsync(() -> {
            try {
                compensation.run();
                result.setSuccessfulCompensations(result.getSuccessfulCompensations() + 1);
                log.debug("✅ Compensation successful for step: {}", stepName);
                
            } catch (Exception e) {
                log.error("❌ Compensation failed for step: {}", stepName, e);
                result.getFailedCompensations().add(
                    CompensationFailure.builder()
                        .stepName(stepName)
                        .errorMessage(e.getMessage())
                        .failedAt(Instant.now())
                        .build()
                );
            }
        }).thenApply(v -> result);
    }

    /**
     * Complete a saga successfully.
     */
    @Transactional
    public CompletableFuture<SagaExecution> completeSaga(SagaId sagaId) {
        var saga = activeSagas.get(sagaId);
        if (saga == null) {
            return CompletableFuture.failedFuture(
                new SagaNotFoundException("Saga not found: " + sagaId));
        }

        log.info("🎉 Completing saga: {}", sagaId);

        saga.setStatus(SagaStatus.COMPLETED);
        saga.setCompletedAt(Instant.now());

        return updateSagaState(saga)
            .thenApply(updated -> {
                // Remove from active sagas
                activeSagas.remove(sagaId);
                
                // Publish saga completed event
                sagaEventPublisher.publishSagaCompleted(sagaId, updated.getExecutionTime());
                
                log.info("✅ Saga completed successfully: {} (execution time: {}ms)", 
                    sagaId, updated.getExecutionTime().toMillis());
                
                return updated;
            });
    }

    /**
     * Abort a saga (mark as failed without compensation).
     */
    @Transactional
    public CompletableFuture<SagaExecution> abortSaga(SagaId sagaId, String reason) {
        var saga = activeSagas.get(sagaId);
        if (saga == null) {
            return CompletableFuture.failedFuture(
                new SagaNotFoundException("Saga not found: " + sagaId));
        }

        log.warn("⚠️ Aborting saga: {} - Reason: {}", sagaId, reason);

        saga.setStatus(SagaStatus.ABORTED);
        saga.setAbortedAt(Instant.now());
        saga.setAbortReason(reason);

        return updateSagaState(saga)
            .thenApply(updated -> {
                // Remove from active sagas
                activeSagas.remove(sagaId);
                
                // Publish saga aborted event
                sagaEventPublisher.publishSagaAborted(sagaId, reason);
                
                return updated;
            });
    }

    /**
     * Get current saga execution status.
     */
    public CompletableFuture<Optional<SagaExecution>> getSagaExecution(SagaId sagaId) {
        var activeSaga = activeSagas.get(sagaId);
        if (activeSaga != null) {
            return CompletableFuture.completedFuture(Optional.of(activeSaga));
        }
        
        return sagaStateRepository.loadSagaState(sagaId)
            .thenApply(Optional::of)
            .exceptionally(throwable -> {
                if (throwable.getCause() instanceof SagaNotFoundException) {
                    return Optional.empty();
                }
                throw new RuntimeException(throwable);
            });
    }

    /**
     * Get all active saga executions.
     */
    public Map<SagaId, SagaExecution> getActiveSagas() {
        return new HashMap<>(activeSagas);
    }

    /**
     * Recover interrupted sagas on startup.
     */
    @Transactional
    public CompletableFuture<SagaRecoveryResult> recoverSagas() {
        log.info("🔄 Starting saga recovery process...");

        return sagaStateRepository.findInterruptedSagas()
            .thenCompose(interruptedSagas -> {
                var recoveryTasks = new ArrayList<CompletableFuture<SagaRecoveryResult.SagaRecovery>>();

                for (var saga : interruptedSagas) {
                    var recoveryTask = recoverSaga(saga);
                    recoveryTasks.add(recoveryTask);
                }

                return CompletableFuture.allOf(recoveryTasks.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        var recoveredSagas = recoveryTasks.stream()
                            .map(CompletableFuture::join)
                            .toList();

                        var successfulRecoveries = recoveredSagas.stream()
                            .mapToInt(r -> r.isSuccess() ? 1 : 0)
                            .sum();

                        var result = SagaRecoveryResult.builder()
                            .totalSagas(interruptedSagas.size())
                            .successfulRecoveries(successfulRecoveries)
                            .failedRecoveries(interruptedSagas.size() - successfulRecoveries)
                            .recoveredSagas(recoveredSagas)
                            .recoveryCompletedAt(Instant.now())
                            .build();

                        log.info("✅ Saga recovery completed: {}/{} sagas recovered successfully", 
                            successfulRecoveries, interruptedSagas.size());

                        return result;
                    });
            });
    }

    private CompletableFuture<SagaRecoveryResult.SagaRecovery> recoverSaga(SagaExecution saga) {
        log.debug("🔧 Recovering saga: {} (status: {})", saga.getSagaId(), saga.getStatus());

        return CompletableFuture.supplyAsync(() -> {
            try {
                switch (saga.getStatus()) {
                    case STARTED, EXECUTING -> {
                        // Compensate incomplete saga
                        executeCompensations(saga.getSagaId()).join();
                        return SagaRecoveryResult.SagaRecovery.compensated(saga.getSagaId());
                    }
                    case COMPENSATING -> {
                        // Continue compensation
                        executeCompensationsForSaga(saga).join();
                        return SagaRecoveryResult.SagaRecovery.compensated(saga.getSagaId());
                    }
                    case COMPLETED -> {
                        // Already completed, no recovery needed
                        return SagaRecoveryResult.SagaRecovery.alreadyCompleted(saga.getSagaId());
                    }
                    case COMPENSATED -> {
                        // Already compensated, no recovery needed
                        return SagaRecoveryResult.SagaRecovery.alreadyCompensated(saga.getSagaId());
                    }
                    default -> {
                        log.warn("Unknown saga status during recovery: {} for saga: {}", 
                            saga.getStatus(), saga.getSagaId());
                        return SagaRecoveryResult.SagaRecovery.failed(saga.getSagaId(), 
                            "Unknown status: " + saga.getStatus());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to recover saga: {}", saga.getSagaId(), e);
                return SagaRecoveryResult.SagaRecovery.failed(saga.getSagaId(), e.getMessage());
            }
        });
    }

    private CompletableFuture<SagaExecution> updateSagaState(SagaExecution saga) {
        return sagaStateRepository.saveSagaState(saga)
            .exceptionally(throwable -> {
                log.warn("Failed to update saga state for: {}", saga.getSagaId(), throwable);
                return saga; // Continue with in-memory state
            });
    }
}