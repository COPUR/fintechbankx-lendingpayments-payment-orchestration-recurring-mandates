package com.enterprise.openfinance.infrastructure.eventstore;

import com.enterprise.openfinance.domain.port.output.EventStore;
import com.enterprise.openfinance.domain.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL implementation of Event Store with CQRS support.
 * Provides ACID guarantees for event persistence with optimistic locking.
 * 
 * Features:
 * - Event sourcing with snapshots for performance
 * - Optimistic concurrency control
 * - Event replay capabilities
 * - Correlation and causation tracking
 * - PCI-DSS v4 compliant audit trails
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PostgreSQLEventStore implements EventStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EventMetadataEnricher metadataEnricher;
    private final EventEncryptionService encryptionService;
    
    // Snapshot configuration
    private static final int SNAPSHOT_FREQUENCY = 10; // Every 10 events
    private static final int MAX_EVENTS_BEFORE_SNAPSHOT = 100;
    
    // Cache for event type mappings to avoid reflection overhead
    private final Map<String, Class<? extends DomainEvent>> eventTypeCache = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public void saveEvents(String aggregateId, List<DomainEvent> events, long expectedVersion) {
        if (events.isEmpty()) {
            return;
        }

        log.debug("Saving {} events for aggregate: {} at version: {}", 
                events.size(), aggregateId, expectedVersion);

        try {
            // Verify expected version for optimistic locking
            long currentVersion = getCurrentVersion(aggregateId);
            if (currentVersion != expectedVersion) {
                throw new OptimisticLockingFailureException(
                    String.format("Aggregate %s version mismatch. Expected: %d, Actual: %d", 
                        aggregateId, expectedVersion, currentVersion));
            }

            // Save events with sequential versioning
            long nextSequenceNumber = expectedVersion + 1;
            
            for (DomainEvent event : events) {
                saveEvent(aggregateId, event, nextSequenceNumber++);
            }

            // Create snapshot if needed
            if (shouldCreateSnapshot(aggregateId, nextSequenceNumber - 1)) {
                createSnapshot(aggregateId, events.get(events.size() - 1), nextSequenceNumber - 1);
            }

            log.debug("Successfully saved {} events for aggregate: {}", events.size(), aggregateId);

        } catch (Exception e) {
            log.error("Failed to save events for aggregate: {}", aggregateId, e);
            throw new EventStoreException("Failed to save events: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainEvent> getEvents(String aggregateId) {
        return getEvents(aggregateId, 0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainEvent> getEvents(String aggregateId, long fromVersion) {
        log.debug("Loading events for aggregate: {} from version: {}", aggregateId, fromVersion);

        try {
            // Check if we can load from snapshot
            Optional<EventSnapshot> snapshot = getLatestSnapshot(aggregateId);
            long startVersion = fromVersion;
            List<DomainEvent> events = new ArrayList<>();

            if (snapshot.isPresent() && snapshot.get().getSequenceNumber() >= fromVersion) {
                // Load from snapshot
                events.add(deserializeEvent(snapshot.get().getSnapshotData(), 
                    snapshot.get().getAggregateType()));
                startVersion = snapshot.get().getSequenceNumber() + 1;
            }

            // Load remaining events
            String sql = """
                SELECT aggregate_type, event_type, event_version, event_data, metadata, 
                       occurred_at, correlation_id, causation_id
                FROM consent_event_store.events 
                WHERE aggregate_id = ? AND sequence_number >= ? 
                ORDER BY sequence_number ASC
                """;

            List<DomainEvent> additionalEvents = jdbcTemplate.query(sql, 
                this::mapRowToEvent, aggregateId, startVersion);
            
            events.addAll(additionalEvents);

            log.debug("Loaded {} events for aggregate: {}", events.size(), aggregateId);
            return events;

        } catch (Exception e) {
            log.error("Failed to load events for aggregate: {}", aggregateId, e);
            throw new EventStoreException("Failed to load events: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainEvent> getAllEvents(String eventType, Instant from, Instant to) {
        log.debug("Loading all events of type: {} from {} to {}", eventType, from, to);

        try {
            String sql = """
                SELECT aggregate_id, aggregate_type, event_type, event_version, 
                       event_data, metadata, occurred_at, correlation_id, causation_id
                FROM consent_event_store.events 
                WHERE event_type = ? AND occurred_at BETWEEN ? AND ?
                ORDER BY occurred_at ASC
                """;

            List<DomainEvent> events = jdbcTemplate.query(sql, this::mapRowToEvent, 
                eventType, Timestamp.from(from), Timestamp.from(to));

            log.debug("Loaded {} events of type: {}", events.size(), eventType);
            return events;

        } catch (Exception e) {
            log.error("Failed to load events of type: {}", eventType, e);
            throw new EventStoreException("Failed to load events: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long getCurrentVersion(String aggregateId) {
        try {
            String sql = """
                SELECT COALESCE(MAX(sequence_number), 0) 
                FROM consent_event_store.events 
                WHERE aggregate_id = ?
                """;

            Long version = jdbcTemplate.queryForObject(sql, Long.class, aggregateId);
            return version != null ? version : 0L;

        } catch (Exception e) {
            log.error("Failed to get current version for aggregate: {}", aggregateId, e);
            throw new EventStoreException("Failed to get current version: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String aggregateId) {
        try {
            String sql = """
                SELECT EXISTS(
                    SELECT 1 FROM consent_event_store.events 
                    WHERE aggregate_id = ? LIMIT 1
                )
                """;

            Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, aggregateId);
            return exists != null && exists;

        } catch (Exception e) {
            log.error("Failed to check existence for aggregate: {}", aggregateId, e);
            throw new EventStoreException("Failed to check existence: " + e.getMessage(), e);
        }
    }

    // Private helper methods

    private void saveEvent(String aggregateId, DomainEvent event, long sequenceNumber) {
        try {
            // Enrich metadata with correlation/causation info
            Map<String, Object> enrichedMetadata = metadataEnricher.enrichMetadata(event);
            
            // Serialize event data (with encryption for sensitive data)
            String eventData = serializeEventData(event);
            String metadata = objectMapper.writeValueAsString(enrichedMetadata);

            String sql = """
                INSERT INTO consent_event_store.events 
                (aggregate_id, aggregate_type, sequence_number, event_type, event_version,
                 event_data, metadata, occurred_at, correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """;

            int rowsAffected = jdbcTemplate.update(sql,
                aggregateId,
                event.getAggregateType(),
                sequenceNumber,
                event.getClass().getSimpleName(),
                event.getVersion(),
                eventData,
                metadata,
                Timestamp.from(event.getOccurredAt()),
                event.getCorrelationId(),
                event.getCausationId()
            );

            if (rowsAffected != 1) {
                throw new EventStoreException("Failed to insert event. Expected 1 row, got: " + rowsAffected);
            }

        } catch (Exception e) {
            log.error("Failed to save event: {}", event.getClass().getSimpleName(), e);
            throw new EventStoreException("Failed to save event: " + e.getMessage(), e);
        }
    }

    private boolean shouldCreateSnapshot(String aggregateId, long currentVersion) {
        if (currentVersion < SNAPSHOT_FREQUENCY) {
            return false;
        }

        // Check if we already have a recent snapshot
        Optional<EventSnapshot> latestSnapshot = getLatestSnapshot(aggregateId);
        if (latestSnapshot.isEmpty()) {
            return currentVersion >= SNAPSHOT_FREQUENCY;
        }

        long eventsSinceSnapshot = currentVersion - latestSnapshot.get().getSequenceNumber();
        return eventsSinceSnapshot >= SNAPSHOT_FREQUENCY || currentVersion >= MAX_EVENTS_BEFORE_SNAPSHOT;
    }

    private void createSnapshot(String aggregateId, DomainEvent lastEvent, long sequenceNumber) {
        try {
            log.debug("Creating snapshot for aggregate: {} at sequence: {}", aggregateId, sequenceNumber);

            // Serialize the aggregate state (represented by the last event)
            String snapshotData = serializeEventData(lastEvent);

            String sql = """
                INSERT INTO consent_event_store.snapshots 
                (aggregate_id, aggregate_type, sequence_number, snapshot_data, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (aggregate_id) 
                DO UPDATE SET 
                    sequence_number = EXCLUDED.sequence_number,
                    snapshot_data = EXCLUDED.snapshot_data,
                    created_at = EXCLUDED.created_at
                """;

            jdbcTemplate.update(sql,
                aggregateId,
                lastEvent.getAggregateType(),
                sequenceNumber,
                snapshotData,
                Timestamp.from(Instant.now())
            );

            log.debug("Created snapshot for aggregate: {} at sequence: {}", aggregateId, sequenceNumber);

        } catch (Exception e) {
            log.error("Failed to create snapshot for aggregate: {}", aggregateId, e);
            // Don't throw exception for snapshot failures - they're performance optimizations
        }
    }

    private Optional<EventSnapshot> getLatestSnapshot(String aggregateId) {
        try {
            String sql = """
                SELECT aggregate_id, aggregate_type, sequence_number, snapshot_data, created_at
                FROM consent_event_store.snapshots 
                WHERE aggregate_id = ?
                """;

            List<EventSnapshot> snapshots = jdbcTemplate.query(sql, this::mapRowToSnapshot, aggregateId);
            return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.get(0));

        } catch (Exception e) {
            log.warn("Failed to load snapshot for aggregate: {}", aggregateId, e);
            return Optional.empty();
        }
    }

    private DomainEvent mapRowToEvent(ResultSet rs, int rowNum) throws SQLException {
        try {
            String eventType = rs.getString("event_type");
            String eventData = rs.getString("event_data");
            String aggregateType = rs.getString("aggregate_type");

            return deserializeEvent(eventData, aggregateType, eventType);

        } catch (Exception e) {
            log.error("Failed to map row to event at row: {}", rowNum, e);
            throw new SQLException("Failed to map row to event", e);
        }
    }

    private EventSnapshot mapRowToSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return EventSnapshot.builder()
            .aggregateId(rs.getString("aggregate_id"))
            .aggregateType(rs.getString("aggregate_type"))
            .sequenceNumber(rs.getLong("sequence_number"))
            .snapshotData(rs.getString("snapshot_data"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .build();
    }

    private String serializeEventData(DomainEvent event) throws Exception {
        // Check if event contains sensitive data that needs encryption
        if (containsSensitiveData(event)) {
            return encryptionService.encrypt(objectMapper.writeValueAsString(event));
        }
        return objectMapper.writeValueAsString(event);
    }

    private DomainEvent deserializeEvent(String eventData, String aggregateType) throws Exception {
        return deserializeEvent(eventData, aggregateType, null);
    }

    private DomainEvent deserializeEvent(String eventData, String aggregateType, String eventType) throws Exception {
        // Decrypt if necessary
        String decryptedData = eventData;
        if (encryptionService.isEncrypted(eventData)) {
            decryptedData = encryptionService.decrypt(eventData);
        }

        // Get event class from cache or resolve it
        Class<? extends DomainEvent> eventClass = getEventClass(aggregateType, eventType);
        
        return objectMapper.readValue(decryptedData, eventClass);
    }

    private Class<? extends DomainEvent> getEventClass(String aggregateType, String eventType) {
        String cacheKey = aggregateType + ":" + eventType;
        return eventTypeCache.computeIfAbsent(cacheKey, key -> {
            try {
                // Use a registry or convention-based approach to resolve event classes
                String packageName = "com.enterprise.openfinance.domain.event";
                String className = packageName + "." + eventType;
                
                @SuppressWarnings("unchecked")
                Class<? extends DomainEvent> clazz = (Class<? extends DomainEvent>) Class.forName(className);
                return clazz;
                
            } catch (ClassNotFoundException e) {
                throw new EventStoreException("Cannot find event class for: " + eventType, e);
            }
        });
    }

    private boolean containsSensitiveData(DomainEvent event) {
        // Check if event contains PCI-DSS regulated data
        String eventData = event.toString().toLowerCase();
        return eventData.contains("pan") || 
               eventData.contains("card") || 
               eventData.contains("account_number") ||
               eventData.contains("cvv");
    }

    // Inner classes
    
    private static class EventSnapshot {
        private final String aggregateId;
        private final String aggregateType;
        private final long sequenceNumber;
        private final String snapshotData;
        private final Instant createdAt;

        private EventSnapshot(String aggregateId, String aggregateType, long sequenceNumber, 
                             String snapshotData, Instant createdAt) {
            this.aggregateId = aggregateId;
            this.aggregateType = aggregateType;
            this.sequenceNumber = sequenceNumber;
            this.snapshotData = snapshotData;
            this.createdAt = createdAt;
        }

        public static EventSnapshotBuilder builder() {
            return new EventSnapshotBuilder();
        }

        // Getters
        public String getAggregateId() { return aggregateId; }
        public String getAggregateType() { return aggregateType; }
        public long getSequenceNumber() { return sequenceNumber; }
        public String getSnapshotData() { return snapshotData; }
        public Instant getCreatedAt() { return createdAt; }

        public static class EventSnapshotBuilder {
            private String aggregateId;
            private String aggregateType;
            private long sequenceNumber;
            private String snapshotData;
            private Instant createdAt;

            public EventSnapshotBuilder aggregateId(String aggregateId) {
                this.aggregateId = aggregateId;
                return this;
            }

            public EventSnapshotBuilder aggregateType(String aggregateType) {
                this.aggregateType = aggregateType;
                return this;
            }

            public EventSnapshotBuilder sequenceNumber(long sequenceNumber) {
                this.sequenceNumber = sequenceNumber;
                return this;
            }

            public EventSnapshotBuilder snapshotData(String snapshotData) {
                this.snapshotData = snapshotData;
                return this;
            }

            public EventSnapshotBuilder createdAt(Instant createdAt) {
                this.createdAt = createdAt;
                return this;
            }

            public EventSnapshot build() {
                return new EventSnapshot(aggregateId, aggregateType, sequenceNumber, snapshotData, createdAt);
            }
        }
    }

    public static class EventStoreException extends RuntimeException {
        public EventStoreException(String message) {
            super(message);
        }

        public EventStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}