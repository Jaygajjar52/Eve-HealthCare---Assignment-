package com.eve.diagnostics.service;

import com.eve.diagnostics.config.AppProperties;
import com.eve.diagnostics.dto.request.WebhookRequest;
import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.entity.Payment;
import com.eve.diagnostics.entity.WebhookEvent;
import com.eve.diagnostics.enums.BookingStatus;
import com.eve.diagnostics.enums.PaymentStatus;
import com.eve.diagnostics.exception.WebhookSignatureException;
import com.eve.diagnostics.repository.PaymentRepository;
import com.eve.diagnostics.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

/**
 * ============================================================================
 *  IDEMPOTENCY STRATEGY  -  read this before changing anything in here
 * ============================================================================
 *
 * A payment provider will retry a webhook until it gets a 2xx, and it can also
 * send the same event twice at the same moment. So "have I already handled
 * eventId?" gets asked constantly, and the answer has to be right under
 * concurrency.
 *
 * The obvious implementation is wrong:
 *
 *     if (repository.findByEventId(id).isEmpty()) { process(); }
 *
 * Two concurrent copies both see no row, both process, and the customer gets
 * told their payment failed and then that it succeeded. A read followed by a
 * write is a race, whatever the code looks like.
 *
 * What we do instead: webhook_events.event_id carries a UNIQUE constraint, and
 * the row is INSERTed before anything is acted on. The database, not our code,
 * decides the winner - only one INSERT can succeed for a given eventId, and the
 * losers get a constraint violation. Those losers have provably changed nothing,
 * so they answer 200 ALREADY_PROCESSED.
 *
 * Two consequences that are easy to get wrong, and how they are handled:
 *
 * 1. The INSERT is flushed explicitly with saveAndFlush(). Without the flush the
 *    INSERT would not run until commit, the constraint violation would surface
 *    outside this method, and we could not recognise it as a duplicate.
 *
 * 2. The insert and the processing share ONE transaction, so an event can never
 *    be recorded without being processed (or the other way round). That means
 *    the duplicate has to abort the transaction rather than carry on: in
 *    PostgreSQL a failed statement poisons the whole transaction, so simply
 *    catching the exception and returning would blow up at commit. We therefore
 *    throw DuplicateEventException out of the transaction and catch it outside,
 *    where the rollback has already happened and the outer transaction is
 *    untouched.
 *
 *    That is also why this class uses TransactionTemplate rather than
 *    @Transactional on the method. @Transactional only takes effect through a
 *    Spring proxy, so a self-call inside the same bean would silently run with no
 *    transaction at all - the exact opposite of what we need here.
 *
 * Everything else is about being a good webhook receiver: never downgrade a
 * confirmed payment, never resurrect a cancelled booking, and never invent a
 * payment or booking that does not already exist.
 * ============================================================================
 */
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public static final String RESULT_PROCESSED = "PROCESSED";
    public static final String RESULT_ALREADY_PROCESSED = "ALREADY_PROCESSED";
    public static final String RESULT_PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    public static final String RESULT_BOOKING_MISMATCH = "BOOKING_MISMATCH";
    public static final String RESULT_NOOP = "NOOP_ALREADY_IN_THAT_STATE";
    public static final String RESULT_IGNORED_INVALID_TRANSITION = "IGNORED_INVALID_TRANSITION";
    public static final String RESULT_SUCCESS_ON_CANCELLED_BOOKING = "SUCCESS_ON_CANCELLED_BOOKING";

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final AppProperties properties;
    private final TransactionTemplate transactionTemplate;

    /**
     * Checks the provider's signature over the exact bytes we received.
     *
     * <p>Called before the body is parsed, because re-serialising the parsed
     * object could produce different bytes (key order, spacing) and produce a
     * different HMAC - which would make a perfectly valid webhook look forged.
     *
     * @throws WebhookSignatureException if the header is missing, is not hex, or
     *         does not match. Nothing is written when this throws.
     */
    public void verifySignature(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookSignatureException();
        }

        byte[] provided = decodeHex(signatureHeader.trim());
        byte[] expected = sign(rawBody);

        // MessageDigest.isEqual compares in time that does not depend on where
        // the first difference is, so an attacker cannot learn the correct
        // signature one byte at a time by timing the response.
        if (provided == null || !MessageDigest.isEqual(expected, provided)) {
            log.warn("Rejected a webhook with an invalid signature");
            throw new WebhookSignatureException();
        }
    }

    /**
     * Records the event and applies it, exactly once.
     */
    public WebhookResult process(WebhookRequest request, String rawPayload) {
        try {
            return transactionTemplate.execute(status -> claimAndProcess(request, rawPayload));
        } catch (DuplicateEventException duplicate) {
            // The transaction has already rolled back, so nothing was changed.
            log.info("Webhook eventId={} was already processed, ignoring the duplicate", request.eventId());
            return new WebhookResult(RESULT_ALREADY_PROCESSED, Outcome.ALREADY_PROCESSED);
        }
    }

    private WebhookResult claimAndProcess(WebhookRequest request, String rawPayload) {
        WebhookEvent event = WebhookEvent.builder()
                .eventId(request.eventId())
                .paymentId(request.paymentId())
                .bookingId(request.bookingId())
                .payload(rawPayload)
                .statusReceived(request.status())
                .build();

        // The insert IS the idempotency check. Flushing here is what makes a
        // duplicate surface as an exception we can recognise, inside this
        // method, instead of silently at commit time.
        try {
            webhookEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEventException();
        }

        log.info("Webhook received eventId={} paymentId={} bookingId={} status={}",
                request.eventId(), request.paymentId(), request.bookingId(), request.status());

        return applyEvent(event, request);
    }

    private WebhookResult applyEvent(WebhookEvent event, WebhookRequest request) {
        Optional<Payment> foundPayment = paymentRepository.findById(request.paymentId());
        if (foundPayment.isEmpty()) {
            // Never create a payment from a webhook: we only ever reflect what
            // our own POST /payments already did. The event is still recorded so
            // there is an audit trail of what the provider claims to have sent.
            return record(event, RESULT_PAYMENT_NOT_FOUND, Outcome.PAYMENT_NOT_FOUND);
        }

        Payment payment = foundPayment.get();
        Booking booking = payment.getBooking();

        // A provider that mixes up ids must not be able to move an unrelated
        // booking, so we refuse the whole event rather than trusting one id.
        if (!booking.getId().equals(request.bookingId())) {
            log.warn("Webhook eventId={} claims bookingId={} but payment {} belongs to booking {}",
                    request.eventId(), request.bookingId(), payment.getId(), booking.getId());
            return record(event, RESULT_BOOKING_MISMATCH, Outcome.BOOKING_MISMATCH);
        }

        if (payment.getStatus() == request.status()) {
            return record(event, RESULT_NOOP, Outcome.PROCESSED);
        }

        // Out-of-order delivery: providers retry, so a late FAILED can arrive
        // after the SUCCESS that already confirmed the booking. Accepting it
        // would tell the customer their confirmed booking failed.
        boolean wouldDowngrade = request.status() == PaymentStatus.FAILED
                && (payment.getStatus() == PaymentStatus.SUCCESS
                || booking.getStatus() == BookingStatus.CONFIRMED);
        if (wouldDowngrade) {
            log.warn("Ignoring out-of-order FAILED webhook eventId={} for payment {}: "
                    + "payment is {} and booking is {} and neither may be downgraded",
                    request.eventId(), payment.getId(), payment.getStatus(), booking.getStatus());
            return record(event, RESULT_IGNORED_INVALID_TRANSITION, Outcome.PROCESSED);
        }

        // Money arrived for a booking the customer cancelled. The payment is
        // recorded as SUCCESS because that is the truth about the money, but the
        // booking stays CANCELLED and the event is flagged so a human can refund.
        if (booking.getStatus() == BookingStatus.CANCELLED && request.status() == PaymentStatus.SUCCESS) {
            log.warn("SUCCESS webhook eventId={} arrived for cancelled booking {} - flagging for manual refund",
                    request.eventId(), booking.getId());
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setFailureReason(null);
            return record(event, RESULT_SUCCESS_ON_CANCELLED_BOOKING, Outcome.PROCESSED);
        }

        BookingStatus target = request.status() == PaymentStatus.SUCCESS
                ? BookingStatus.CONFIRMED
                : BookingStatus.FAILED;

        // A provider correcting a failure to a success is a legitimate recovery,
        // but FAILED may only move to PENDING, so the booking passes through
        // PENDING first. Same hop PaymentService makes when a user retries.
        booking.reopenForAnotherPaymentAttempt();

        if (!booking.canTransitionTo(target)) {
            log.warn("Ignoring webhook eventId={}: booking {} cannot move from {} to {}",
                    request.eventId(), booking.getId(), booking.getStatus(), target);
            return record(event, RESULT_IGNORED_INVALID_TRANSITION, Outcome.PROCESSED);
        }

        payment.setStatus(request.status());
        payment.setFailureReason(request.status() == PaymentStatus.FAILED
                ? "Reported as failed by the payment provider."
                : null);
        booking.transitionTo(target);

        log.info("Webhook eventId={} applied: payment {} is {}, booking {} is {}",
                request.eventId(), payment.getId(), payment.getStatus(), booking.getId(), booking.getStatus());

        return record(event, RESULT_PROCESSED, Outcome.PROCESSED);
    }

    private WebhookResult record(WebhookEvent event, String result, Outcome outcome) {
        event.setProcessingResult(result);
        event.setProcessedAt(Instant.now());
        return new WebhookResult(result, outcome);
    }

    private byte[] sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getWebhook().getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not compute the webhook signature", ex);
        }
    }

    /** Hex-encoded signature back to bytes, or null if the header is not hex. */
    private byte[] decodeHex(String signature) {
        if (signature.length() % 2 != 0) {
            return null;
        }
        try {
            byte[] bytes = new byte[signature.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(signature.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Internal signal only; never escapes this class. */
    private static class DuplicateEventException extends RuntimeException {
    }

    /**
     * What happened to the event, so the controller can pick a status code. The
     * service does not throw for "we ignored this", because throwing would roll
     * back the audit row we just wrote.
     */
    public record WebhookResult(String result, Outcome outcome) {
    }

    public enum Outcome {
        /** Understood and applied, or deliberately ignored. Always 200. */
        PROCESSED,
        /** A copy of an event we already have. Always 200. */
        ALREADY_PROCESSED,
        /** We have never seen that payment. */
        PAYMENT_NOT_FOUND,
        /** paymentId and bookingId disagree. */
        BOOKING_MISMATCH
    }
}