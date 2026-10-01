package com.eve.diagnostics.entity;

import com.eve.diagnostics.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One received payment webhook, written before it is processed.
 *
 * <p>The UNIQUE constraint on {@code eventId} is the idempotency guard for the
 * whole webhook flow: the row is inserted first, and only the request that wins
 * that insert is allowed to act. Two identical webhooks arriving at the same
 * moment therefore cannot both process - one of them gets a constraint violation
 * and answers ALREADY_PROCESSED.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Idempotency key supplied by the payment provider. UNIQUE. */
    @Column(name = "event_id", nullable = false, unique = true, length = 120)
    private String eventId;

    /**
     * Not a foreign key on purpose. A webhook can arrive for a payment we have
     * never seen, and we still have to record the event (with result
     * PAYMENT_NOT_FOUND) so there is an audit trail. A real FK would block
     * that insert.
     */
    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    /** Same reasoning as {@link #paymentId}. */
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    /** Raw body as received, kept for auditing. Never parsed for logic. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_received", nullable = false, length = 20)
    private PaymentStatus statusReceived;

    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * Why the event ended the way it did: PROCESSED, ALREADY_PROCESSED,
     * PAYMENT_NOT_FOUND, IGNORED_INVALID_TRANSITION,
     * SUCCESS_ON_CANCELLED_BOOKING, ...
     */
    @Column(name = "processing_result", length = 60)
    private String processingResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}