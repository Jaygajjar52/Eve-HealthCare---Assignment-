package com.eve.diagnostics.service;

import com.eve.diagnostics.config.AppProperties;
import com.eve.diagnostics.dto.request.PaymentRequest;
import com.eve.diagnostics.dto.response.PaymentResponse;
import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.entity.Payment;
import com.eve.diagnostics.enums.BookingStatus;
import com.eve.diagnostics.enums.PaymentStatus;
import com.eve.diagnostics.exception.BadRequestException;
import com.eve.diagnostics.exception.ConflictException;
import com.eve.diagnostics.exception.InvalidBookingStateException;
import com.eve.diagnostics.exception.ResourceNotFoundException;
import com.eve.diagnostics.repository.BookingRepository;
import com.eve.diagnostics.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Stand-in for a real gateway reference. */
    private static final String PROVIDER_REFERENCE_PREFIX = "MOCKPAY-";

    private static final String FAILURE_REASON = "Declined by the simulated payment gateway.";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final AppProperties properties;
    private final Environment environment;

    /**
     * Charges for a booking against the simulated gateway.
     *
     * <p>The whole method is one transaction on purpose. The payment row and the
     * booking's new status have to agree: a transaction that saved a SUCCESS
     * payment but failed to move the booking to CONFIRMED would leave the
     * customer charged with nothing to show for it, and nothing would ever
     * repair it.
     *
     * <p>Booking has @Version, so if two of these run at once for the same
     * booking, the second one's UPDATE matches no row and Hibernate raises
     * ObjectOptimisticLockingFailureException, which becomes a 409 telling the
     * client to retry. Without it, both would read PENDING and both would charge.
     */
    @Transactional
    public PaymentResponse createPayment(UUID userId, PaymentRequest request, String idempotencyKey) {
        Optional<PaymentResponse> replay = replayIdempotentRequest(userId, request, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        Booking booking = findBookingForUser(userId, request.bookingId());
        ensureBookingIsPayable(booking);

        // A retry after a failed attempt has to pass through PENDING first,
        // because FAILED is only allowed to move there. The new outcome is then
        // applied from PENDING exactly like a first attempt, so one code path
        // serves both.
        booking.reopenForAnotherPaymentAttempt();

        PaymentStatus outcome = decideOutcome(request.simulateStatus());

        Payment payment = paymentRepository.save(Payment.builder()
                .booking(booking)
                .amount(booking.getAmount())
                .status(outcome)
                .providerReference(PROVIDER_REFERENCE_PREFIX + UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .failureReason(outcome == PaymentStatus.FAILED ? FAILURE_REASON : null)
                .build());

        applyOutcomeToBooking(booking, outcome);

        log.info("Payment {} for booking={} user={} outcome={} bookingStatus={}",
                payment.getId(), booking.getId(), userId, outcome, booking.getStatus());

        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id " + paymentId));

        // Same reasoning as bookings: someone else's payment is reported as
        // missing so payment ids cannot be probed either.
        if (!payment.getBooking().getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Payment not found with id " + paymentId);
        }

        return PaymentResponse.from(payment);
    }

    /**
     * If this request carries an Idempotency-Key we have already used, hand back
     * the payment that key originally produced instead of charging again.
     *
     * <p>Assumption: the key identifies one payment attempt for one user, so
     * reusing it for a different booking is a client bug and is refused rather
     * than silently returning the wrong payment.
     */
    private Optional<PaymentResponse> replayIdempotentRequest(UUID userId, PaymentRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        return paymentRepository.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getBooking().getUser().getId().equals(userId)) {
                throw new ConflictException("That Idempotency-Key belongs to a different user.");
            }
            if (!existing.getBooking().getId().equals(request.bookingId())) {
                throw new ConflictException("That Idempotency-Key was already used for a different booking.");
            }
            log.info("Replaying idempotent payment {} for key={}", existing.getId(), idempotencyKey);
            return PaymentResponse.from(existing);
        });
    }

    private Booking findBookingForUser(UUID userId, UUID bookingId) {
        return bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id " + bookingId));
    }

    private void ensureBookingIsPayable(Booking booking) {
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            throw new ConflictException("This booking has already been paid for.");
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new ConflictException("This booking was cancelled and cannot be paid for.");
        }
        if (paymentRepository.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCESS)) {
            throw new ConflictException("A successful payment already exists for this booking.");
        }
    }

    private void applyOutcomeToBooking(Booking booking, PaymentStatus outcome) {
        BookingStatus target = outcome == PaymentStatus.SUCCESS
                ? BookingStatus.CONFIRMED
                : BookingStatus.FAILED;

        if (!booking.canTransitionTo(target)) {
            throw new InvalidBookingStateException(
                    "A booking with status " + booking.getStatus() + " cannot become " + target + ".");
        }
        booking.transitionTo(target);
    }

    /**
     * Either the caller forces the result (tests, demos) or we roll the dice
     * using eve.payment.success-rate.
     */
    private PaymentStatus decideOutcome(PaymentStatus requested) {
        if (requested != null) {
            if (isProduction()) {
                throw new BadRequestException(
                        "simulateStatus cannot be used when the application runs the 'prod' profile.");
            }
            log.info("Using requested simulateStatus={} instead of the configured success rate", requested);
            return requested;
        }

        double successRate = properties.getPayment().getSuccessRate();
        return ThreadLocalRandom.current().nextDouble() < successRate
                ? PaymentStatus.SUCCESS
                : PaymentStatus.FAILED;
    }

    private boolean isProduction() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }
}