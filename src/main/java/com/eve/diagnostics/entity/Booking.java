package com.eve.diagnostics.entity;

import com.eve.diagnostics.enums.BookingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A user's reservation of one test at one centre, at a fixed price. */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The centre + test + price row this booking was made from. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "centre_test_id", nullable = false)
    private CentreTest centreTest;

    /**
     * Copied from centreTest. Keeping centre and test directly on the booking
     * means listings and reports need one join less, and the booking stays
     * readable even if the centre_tests row is later edited.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "centre_id", nullable = false)
    private DiagnosticCentre centre;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_id", nullable = false)
    private DiagnosticTest test;

    @Column(name = "appointment_time", nullable = false)
    private Instant appointmentTime;

    /**
     * Price snapshot taken from centre_tests.price at creation time.
     * See the comment block at the bottom of V1__init.sql for why a later price
     * change must not rewrite old bookings.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking. Hibernate turns every update into
     * "UPDATE bookings SET ... WHERE id = ? AND version = ?", so if two requests
     * read the same booking and both try to change it, the second one fails with
     * ObjectOptimisticLockingFailureException instead of silently overwriting.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * Timestamps are set here rather than with @CreationTimestamp / @UpdateTimestamp
     * because those only fill in during the SQL flush, which is too late to read
     * straight after save(). @PrePersist runs during save() itself, so both values
     * are already usable when the service builds its response DTO.
     */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * The booking state machine, in the only place it is written down.
     *
     * <pre>
     *   PENDING   -> CONFIRMED | FAILED | CANCELLED
     *   FAILED    -> PENDING          (user retries the payment)
     *   CONFIRMED -> CANCELLED
     *   CANCELLED -> (nothing, terminal)
     * </pre>
     *
     * <p>Every change of status in the whole application asks this method
     * first. Keeping the rules here rather than scattered through the payment
     * and webhook services is what stops those two from disagreeing about what
     * is allowed.
     *
     * <p>Note that FAILED -> PENDING exists so a customer can pay again after a
     * failed attempt. It is an assumption, not something the payment gateway
     * requires: without it a failed booking could never be recovered.
     */
    public boolean canTransitionTo(BookingStatus newStatus) {
        return switch (status) {
            case PENDING -> newStatus == BookingStatus.CONFIRMED
                    || newStatus == BookingStatus.FAILED
                    || newStatus == BookingStatus.CANCELLED;
            case FAILED -> newStatus == BookingStatus.PENDING;
            case CONFIRMED -> newStatus == BookingStatus.CANCELLED;
            case CANCELLED -> false;
        };
    }

    /**
     * Moves to a new status. Callers must have checked
     * {@link #canTransitionTo(BookingStatus)} first and thrown otherwise, which
     * keeps the HTTP status code decision in the service layer.
     */
    public void transitionTo(BookingStatus newStatus) {
        this.status = newStatus;
    }
}