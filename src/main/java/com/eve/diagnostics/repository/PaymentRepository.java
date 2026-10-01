package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.Payment;
import com.eve.diagnostics.enums.PaymentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * "Has this booking already been paid?" - a booking may have several payment
     * attempts, but at most one of them may have succeeded.
     */
    boolean existsByBookingIdAndStatus(UUID bookingId, PaymentStatus status);

    /**
     * Fallback lookup for the webhook, which may address a payment by the
     * gateway's own reference instead of our id.
     */
    Optional<Payment> findByProviderReference(String providerReference);

    /**
     * Replay of a retried request carrying the same "Idempotency-Key".
     * UNIQUE in the schema, so two concurrent requests with one key cannot both
     * create a payment.
     */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * The graph pulls in the booking and its owner, because PaymentResponse
     * reports the booking status and getPayment has to check ownership. Without
     * it those would be extra round trips.
     */
    @Override
    @EntityGraph(attributePaths = {"booking", "booking.user"})
    Optional<Payment> findById(UUID id);
}