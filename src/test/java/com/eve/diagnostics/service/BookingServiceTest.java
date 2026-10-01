package com.eve.diagnostics.service;

import com.eve.diagnostics.dto.request.BookingRequest;
import com.eve.diagnostics.dto.response.BookingResponse;
import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.entity.CentreTest;
import com.eve.diagnostics.entity.DiagnosticCentre;
import com.eve.diagnostics.entity.DiagnosticTest;
import com.eve.diagnostics.entity.User;
import com.eve.diagnostics.enums.BookingStatus;
import com.eve.diagnostics.enums.Role;
import com.eve.diagnostics.exception.InvalidBookingStateException;
import com.eve.diagnostics.exception.ResourceNotFoundException;
import com.eve.diagnostics.repository.BookingRepository;
import com.eve.diagnostics.repository.CentreTestRepository;
import com.eve.diagnostics.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CENTRE_ID = UUID.randomUUID();
    private static final UUID TEST_ID = UUID.randomUUID();

    @Mock private BookingRepository bookingRepository;
    @Mock private CentreTestRepository centreTestRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private BookingService bookingService;

    private DiagnosticCentre centre;
    private DiagnosticTest test;
    private CentreTest centreTest;
    private User user;

    @BeforeEach
    void setUp() {
        centre = DiagnosticCentre.builder().id(CENTRE_ID).name("Andheri Centre").city("Mumbai").build();
        test = DiagnosticTest.builder().id(TEST_ID).name("Complete Blood Count").build();
        centreTest = CentreTest.builder().id(UUID.randomUUID())
                .centre(centre).test(test).price(new BigDecimal("350.00")).build();
        user = User.builder().id(USER_ID).name("Asha").email("asha@eve.com")
                .passwordHash("hash").role(Role.USER).build();

        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
    }

    private BookingRequest request(Instant appointmentTime) {
        return new BookingRequest(CENTRE_ID, TEST_ID, appointmentTime);
    }

    /** Saves whatever the service built, then hands the same instance back. */
    private void persistAndReturn() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Booking existingBooking(BookingStatus status) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID()).user(user).centreTest(centreTest).centre(centre).test(test)
                .appointmentTime(Instant.now().plus(3, ChronoUnit.DAYS))
                .amount(new BigDecimal("350.00")).status(status)
                .build();
        booking.setCreatedAt(Instant.now());
        booking.setUpdatedAt(Instant.now());
        return booking;
    }

    @Nested
    @DisplayName("createBooking")
    class CreateBooking {

        @Test
        @DisplayName("creates a PENDING booking at the catalogue price")
        void createsPendingBooking() {
            when(centreTestRepository.findByCentreIdAndTestId(CENTRE_ID, TEST_ID)).thenReturn(Optional.of(centreTest));
            persistAndReturn();

            Instant appointment = Instant.now().plus(3, ChronoUnit.DAYS);
            BookingResponse response = bookingService.createBooking(USER_ID, request(appointment));

            assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
            assertThat(response.amount()).isEqualByComparingTo("350.00");
            assertThat(response.appointmentTime()).isEqualTo(appointment);
            assertThat(response.centreId()).isEqualTo(CENTRE_ID);
            assertThat(response.testId()).isEqualTo(TEST_ID);
        }

        @Test
        @DisplayName("copies the price rather than holding a reference to the catalogue row")
        void snapshotsThePrice() {
            when(centreTestRepository.findByCentreIdAndTestId(CENTRE_ID, TEST_ID)).thenReturn(Optional.of(centreTest));
            persistAndReturn();

            bookingService.createBooking(USER_ID, request(Instant.now().plus(1, ChronoUnit.DAYS)));

            ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(saved.capture());
            assertThat(saved.getValue().getAmount()).isEqualByComparingTo("350.00");
        }

        @Test
        @DisplayName("uses the signed-in user, never anything from the request")
        void usesAuthenticatedUser() {
            when(centreTestRepository.findByCentreIdAndTestId(CENTRE_ID, TEST_ID)).thenReturn(Optional.of(centreTest));
            persistAndReturn();

            bookingService.createBooking(USER_ID, request(Instant.now().plus(1, ChronoUnit.DAYS)));

            ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(saved.capture());
            assertThat(saved.getValue().getUser().getId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("rejects a centre/test pair that is not on offer with 404")
        void rejectsUnknownCentreTestPair() {
            when(centreTestRepository.findByCentreIdAndTestId(CENTRE_ID, TEST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.createBooking(USER_ID, request(Instant.now())))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("does not offer test");

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("never queries users for a full entity when only the id is needed")
        void doesNotLoadTheUserRow() {
            when(centreTestRepository.findByCentreIdAndTestId(CENTRE_ID, TEST_ID)).thenReturn(Optional.of(centreTest));
            persistAndReturn();

            bookingService.createBooking(USER_ID, request(Instant.now().plus(1, ChronoUnit.DAYS)));

            verify(userRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("cancelBooking")
    class CancelBooking {

        @Test
        @DisplayName("cancels a PENDING booking")
        void cancelsPendingBooking() {
            Booking booking = existingBooking(BookingStatus.PENDING);
            when(bookingRepository.findByIdAndUserId(booking.getId(), USER_ID)).thenReturn(Optional.of(booking));

            BookingResponse response = bookingService.cancelBooking(USER_ID, booking.getId());

            assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancels a CONFIRMED booking")
        void cancelsConfirmedBooking() {
            Booking booking = existingBooking(BookingStatus.CONFIRMED);
            when(bookingRepository.findByIdAndUserId(booking.getId(), USER_ID)).thenReturn(Optional.of(booking));

            BookingResponse response = bookingService.cancelBooking(USER_ID, booking.getId());

            assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        @DisplayName("refuses to cancel an already CANCELLED booking with 409")
        void refusesDoubleCancel() {
            Booking booking = existingBooking(BookingStatus.CANCELLED);
            when(bookingRepository.findByIdAndUserId(booking.getId(), USER_ID)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelBooking(USER_ID, booking.getId()))
                    .isInstanceOf(InvalidBookingStateException.class)
                    .hasMessageContaining("CANCELLED");

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        @DisplayName("refuses to cancel a FAILED booking with 409")
        void refusesFailedBooking() {
            Booking booking = existingBooking(BookingStatus.FAILED);
            when(bookingRepository.findByIdAndUserId(booking.getId(), USER_ID)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelBooking(USER_ID, booking.getId()))
                    .isInstanceOf(InvalidBookingStateException.class)
                    .hasMessageContaining("FAILED");

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.FAILED);
        }

        @Test
        @DisplayName("answers 404 for a booking belonging to someone else")
        void refusesAnotherUsersBooking() {
            UUID someoneElsesBooking = UUID.randomUUID();
            when(bookingRepository.findByIdAndUserId(someoneElsesBooking, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.cancelBooking(USER_ID, someoneElsesBooking))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Booking not found");
        }
    }

    @Nested
    @DisplayName("getBooking and listBookings")
    class Reads {

        @Test
        @DisplayName("returns 404 rather than 403 for another user's booking")
        void doesNotRevealOtherUsersBookings() {
            UUID someoneElsesBooking = UUID.randomUUID();
            when(bookingRepository.findByIdAndUserId(someoneElsesBooking, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.getBooking(USER_ID, someoneElsesBooking))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Booking not found");
        }

        @Test
        @DisplayName("filters by status only when one is supplied")
        void filtersByStatusWhenPresent() {
            Pageable pageable = PageRequest.of(0, 20);
            when(bookingRepository.findByUserIdAndStatus(USER_ID, BookingStatus.PENDING, pageable))
                    .thenReturn(new PageImpl<>(List.of(existingBooking(BookingStatus.PENDING))));
            when(bookingRepository.findByUserId(USER_ID, pageable))
                    .thenReturn(new PageImpl<>(List.of(existingBooking(BookingStatus.PENDING))));

            bookingService.listBookings(USER_ID, BookingStatus.PENDING, pageable);
            bookingService.listBookings(USER_ID, null, pageable);

            verify(bookingRepository).findByUserIdAndStatus(USER_ID, BookingStatus.PENDING, pageable);
            verify(bookingRepository).findByUserId(USER_ID, pageable);
        }
    }
}