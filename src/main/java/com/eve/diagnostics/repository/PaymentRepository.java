package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.Payment;
import com.eve.diagnostics.enums.PaymentStatus;
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
}