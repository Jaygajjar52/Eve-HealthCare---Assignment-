package com.eve.diagnostics.service;

import com.eve.diagnostics.config.AppProperties;
import com.eve.diagnostics.dto.request.PaymentRequest;
import com.eve.diagnostics.dto.response.PaymentResponse;
import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.entity.CentreTest;
import com.eve.diagnostics.entity.DiagnosticCentre;
import com.eve.diagnostics.entity.DiagnosticTest;
import com.eve.diagnostics.entity.Payment;
import com.eve.diagnostics.entity.User;
import com.eve.diagnostics.enums.BookingStatus;
import com.eve.diagnostics.enums.PaymentStatus;
import com.eve.diagnostics.enums.Role;
import com.eve.diagnostics.exception.ConflictException;
import com.eve.diagnostics.exception.ResourceNotFoundException;
import com.eve.diagnostics.repository.BookingRepository;
import com.eve.diagnostics.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock private BookingRepository bookingRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private AppProperties properties;

    @Spy private AppProperties.Payment paymentProperties = new AppProperties.Payment();

    @Mock private Environment environment = new MockEnvironment();

    @InjectMocks private PaymentService paymentService;

    private Booking booking;

    @BeforeEach
    void setUp() {
        paymentProperties.setSuccessRate(1.0d); // deterministic: always approve
        when(properties.getPayment()).thenReturn(paymentProperties);

        booking = Booking.builder()
                .id(UUID.randomUUID())
                .user(user(USER_ID))
                .centreTest(centreTest())
                .centre(DiagnosticCentre.builder().id(UUID.randomUUID()).name("Andheri").city("Mumbai").build())
                .test(DiagnosticTest.builder().id(UUID.randomUUID()).name("CBC").build())
                .appointmentTime(Instant.now().plus(3, ChronoUnit.DAYS))
                .amount(new BigDecimal("350.00"))
                .status(BookingStatus.PENDING)
                .build();
        booking.setCreatedAt(Instant.now());
        booking.setUpdatedAt(Instant.now());

        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User user(UUID id) {
        return User.builder().id(id).name("User " + id).email(id + "@eve.com")
                .passwordHash("hash").role(Role.USER).build();
    }

    private CentreTest centreTest() {
        DiagnosticCentre centre = DiagnosticCentre.builder().id(UUID.randomUUID()).name("Andheri").city("Mumbai").build();
        return CentreTest.builder().id(UUID.randomUUID()).centre(centre)
                .test(DiagnosticTest.builder().id(UUID.randomUUID()).name("CBC").build())
                .price(new BigDecimal("350.00")).build();
    }

    private void givenBookingIsOwnedBy(BookingStatus status) {
        booking.setStatus(status);
        when(bookingRepository.findByIdAndUserId(booking.getId(), USER_ID)).thenReturn(Optional.of(booking));
    }

    @Test
    @DisplayName("a successful payment confirms the booking")
    void successConfirmsBooking() {
        givenBookingIsOwnedBy(BookingStatus.PENDING);

        PaymentResponse response = paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.amount()).isEqualByComparingTo("350.00");
        assertThat(response.providerReference()).startsWith("MOCKPAY-");
        assertThat(response.failureReason()).isNull();
    }

    @Test
    @DisplayName("a failed payment marks the booking FAILED and records a reason")
    void failureMarksBookingFailed() {
        givenBookingIsOwnedBy(BookingStatus.PENDING);

        PaymentResponse response = paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.FAILED), null);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.FAILED);
        assertThat(response.failureReason()).contains("Declined");
    }

    @Test
    @DisplayName("uses the booking's snapshotted amount, not the live catalogue price")
    void usesSnapshotAmount() {
        booking.setAmount(new BigDecimal("199.99"));
        givenBookingIsOwnedBy(BookingStatus.PENDING);

        PaymentResponse response = paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null);

        assertThat(response.amount()).isEqualByComparingTo("199.99");
    }

    @Test
    @DisplayName("paying an already CONFIRMED booking is refused with 409")
    void refusesConfirmedBooking() {
        givenBookingIsOwnedBy(BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been paid");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("paying a CANCELLED booking is refused with 409")
    void refusesCancelledBooking() {
        givenBookingIsOwnedBy(BookingStatus.CANCELLED);

        assertThatThrownBy(() -> paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancelled");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses to pay when a SUCCESS payment already exists")
    void refusesWhenSuccessAlreadyExists() {
        givenBookingIsOwnedBy(BookingStatus.PENDING);
        when(paymentRepository.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCESS)).thenReturn(true);

        assertThatThrownBy(() -> paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null))
                .isInstanceOf(ConflictException.class);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("retrying a FAILED booking goes back through PENDING and can succeed")
    void retryAfterFailureCanSucceed() {
        givenBookingIsOwnedBy(BookingStatus.FAILED);

        PaymentResponse response = paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null);

        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("another user's booking is reported as missing, not forbidden")
    void refusesAnotherUsersBooking() {
        when(bookingRepository.findByIdAndUserId(booking.getId(), USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Booking not found");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("simulateStatus is rejected on the prod profile")
    void refusesSimulateStatusInProd() {
        givenBookingIsOwnedBy(BookingStatus.PENDING);
        when(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))).thenReturn(true);

        assertThatThrownBy(() -> paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), null))
                .isInstanceOf(com.eve.diagnostics.exception.BadRequestException.class)
                .hasMessageContaining("prod");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("with no simulateStatus the configured success rate decides")
    void usesConfiguredSuccessRate() {
        paymentProperties.setSuccessRate(0.0d);
        givenBookingIsOwnedBy(BookingStatus.PENDING);

        PaymentResponse response = paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), null), null);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("a replayed Idempotency-Key returns the original payment without charging again")
    void replaysIdempotentRequest() {
        givenBookingIsOwnedBy(BookingStatus.PENDING);

        Payment original = Payment.builder()
                .id(UUID.randomUUID()).booking(booking).amount(new BigDecimal("350.00"))
                .status(PaymentStatus.SUCCESS).providerReference("MOCKPAY-original")
                .idempotencyKey("key-123").build();
        original.setCreatedAt(Instant.now());
        when(paymentRepository.findByIdempotencyKey("key-123")).thenReturn(Optional.of(original));

        PaymentResponse response = paymentService.createPayment(
                USER_ID, new PaymentRequest(booking.getId(), PaymentStatus.SUCCESS), "key-123");

        assertThat(response.id()).isEqualTo(original.getId());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("reusing a key for a different booking is refused with 409")
    void refusesKeyReusedForAnotherBooking() {
        givenBookingIsOwnedBy(BookingStatus.PENDING);

        Payment original = Payment.builder()
                .id(UUID.randomUUID()).booking(booking).amount(new BigDecimal("350.00"))
                .status(PaymentStatus.SUCCESS).providerReference("MOCKPAY-original")
                .idempotencyKey("key-123").build();
        when(paymentRepository.findByIdempotencyKey("key-123")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> paymentService.createPayment(
                USER_ID, new PaymentRequest(UUID.randomUUID(), PaymentStatus.SUCCESS), "key-123"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different booking");
    }

    @Test
    @DisplayName("getPayment hides another user's payment behind a 404")
    void getPaymentHidesOtherUsers() {
        givenBookingIsOwnedBy(BookingStatus.CONFIRMED);
        Payment payment = Payment.builder().id(UUID.randomUUID()).booking(booking)
                .amount(new BigDecimal("350.00")).status(PaymentStatus.SUCCESS)
                .providerReference("MOCKPAY-1").build();
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.getPayment(OTHER_USER_ID, payment.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Payment not found");
    }
}