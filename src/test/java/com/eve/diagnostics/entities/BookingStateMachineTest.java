package com.eve.diagnostics.entities;

import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.enums.BookingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state machine is the rule the payment service, the cancel endpoint and the
 * webhook all depend on, so it is tested directly rather than only through the
 * endpoints that use it.
 */
class BookingStateMachineTest {

    private Booking bookingIn(BookingStatus status) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .amount(new BigDecimal("350.00"))
                .status(status)
                .build();
        booking.setCreatedAt(Instant.now());
        booking.setUpdatedAt(Instant.now());
        return booking;
    }

    @Nested
    @DisplayName("allowed transitions")
    class Allowed {

        @ParameterizedTest(name = "PENDING can move to {0}")
        @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "FAILED", "CANCELLED"})
        void fromPending(BookingStatus target) {
            assertThat(bookingIn(BookingStatus.PENDING).canTransitionTo(target)).isTrue();
        }

        @Test
        @DisplayName("FAILED can move back to PENDING so the customer can retry")
        void failedToPending() {
            assertThat(bookingIn(BookingStatus.FAILED).canTransitionTo(BookingStatus.PENDING)).isTrue();
        }

        @Test
        @DisplayName("CONFIRMED can only be cancelled")
        void confirmedToCancelled() {
            Booking booking = bookingIn(BookingStatus.CONFIRMED);
            assertThat(booking.canTransitionTo(BookingStatus.CANCELLED)).isTrue();
            assertThat(booking.canTransitionTo(BookingStatus.PENDING)).isFalse();
            assertThat(booking.canTransitionTo(BookingStatus.FAILED)).isFalse();
            assertThat(booking.canTransitionTo(BookingStatus.CONFIRMED)).isFalse();
        }
    }

    @Nested
    @DisplayName("forbidden transitions")
    class Forbidden {

        @ParameterizedTest(name = "CANCELLED cannot move to {0}, it is terminal")
        @EnumSource(BookingStatus.class)
        void cancelledIsTerminal(BookingStatus target) {
            assertThat(bookingIn(BookingStatus.CANCELLED).canTransitionTo(target)).isFalse();
        }

        @Test
        @DisplayName("FAILED cannot jump straight to CONFIRMED")
        void failedCannotGoDirectlyToConfirmed() {
            // This is why PaymentService and WebhookService both reopen the
            // booking through PENDING before applying an outcome.
            assertThat(bookingIn(BookingStatus.FAILED).canTransitionTo(BookingStatus.CONFIRMED)).isFalse();
            assertThat(bookingIn(BookingStatus.FAILED).canTransitionTo(BookingStatus.CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("PENDING cannot move to itself, which would be a no-op")
        void pendingCannotMoveToItself() {
            assertThat(bookingIn(BookingStatus.PENDING).canTransitionTo(BookingStatus.PENDING)).isFalse();
        }

        @Test
        @DisplayName("a null target is refused rather than throwing")
        void nullTargetIsRefused() {
            assertThat(bookingIn(BookingStatus.PENDING).canTransitionTo(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("reopenForAnotherPaymentAttempt")
    class Reopen {

        @Test
        @DisplayName("a FAILED booking returns to PENDING")
        void failedReopens() {
            Booking booking = bookingIn(BookingStatus.FAILED);

            booking.reopenForAnotherPaymentAttempt();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
            // And is now payable again.
            assertThat(booking.canTransitionTo(BookingStatus.CONFIRMED)).isTrue();
        }

        @Test
        @DisplayName("a PENDING booking is left alone")
        void pendingIsUntouched() {
            Booking booking = bookingIn(BookingStatus.PENDING);

            booking.reopenForAnotherPaymentAttempt();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        }

        @Test
        @DisplayName("a CONFIRMED booking is not reopened")
        void confirmedIsNotReopened() {
            Booking booking = bookingIn(BookingStatus.CONFIRMED);

            booking.reopenForAnotherPaymentAttempt();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("a CANCELLED booking is not resurrected")
        void cancelledIsNotResurrected() {
            Booking booking = bookingIn(BookingStatus.CANCELLED);

            booking.reopenForAnotherPaymentAttempt();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }
    }

    @Test
    @DisplayName("transitionTo sets the status for callers that already checked")
    void transitionToAppliesTheChange() {
        Booking booking = bookingIn(BookingStatus.PENDING);

        booking.transitionTo(BookingStatus.CONFIRMED);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }
}