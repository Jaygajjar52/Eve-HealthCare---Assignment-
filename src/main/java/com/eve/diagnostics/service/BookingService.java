package com.eve.diagnostics.service;

import com.eve.diagnostics.dto.request.BookingRequest;
import com.eve.diagnostics.dto.response.BookingResponse;
import com.eve.diagnostics.dto.response.PageResponse;
import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.entity.CentreTest;
import com.eve.diagnostics.entity.User;
import com.eve.diagnostics.enums.BookingStatus;
import com.eve.diagnostics.exception.InvalidBookingStateException;
import com.eve.diagnostics.exception.ResourceNotFoundException;
import com.eve.diagnostics.repository.BookingRepository;
import com.eve.diagnostics.repository.CentreTestRepository;
import com.eve.diagnostics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final CentreTestRepository centreTestRepository;
    private final UserRepository userRepository;

    @Transactional
    public BookingResponse createBooking(UUID userId, BookingRequest request) {
        // One lookup answers both "does this centre exist" and "does it offer
        // this test". The user asked for a pair that is not on offer, so 404 with
        // a message about the pairing rather than about a missing centre.
        CentreTest centreTest = centreTestRepository
                .findByCentreIdAndTestId(request.centreId(), request.testId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Centre " + request.centreId() + " does not offer test " + request.testId()));

        // getReferenceById returns a lazy stand-in without hitting the database.
        // Only the foreign key is needed here, and the caller came from a valid
        // token, so the account is guaranteed to exist.
        User user = userRepository.getReferenceById(userId);

        Booking booking = bookingRepository.save(Booking.builder()
                .user(user)
                .centreTest(centreTest)
                .centre(centreTest.getCentre())
                .test(centreTest.getTest())
                .appointmentTime(request.appointmentTime())
                // Price snapshot: taken from the catalogue now, and never read
                // from the catalogue again for this booking.
                .amount(centreTest.getPrice())
                .status(BookingStatus.PENDING)
                .build());

        log.info("Booking created id={} user={} centre={} test={} amount={} at={}",
                booking.getId(), userId, booking.getCentre().getId(), booking.getTest().getId(),
                booking.getAmount(), booking.getAppointmentTime());

        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listBookings(UUID userId, BookingStatus status, Pageable pageable) {
        Page<Booking> page = (status == null)
                ? bookingRepository.findByUserId(userId, pageable)
                : bookingRepository.findByUserIdAndStatus(userId, status, pageable);

        return PageResponse.from(page.map(BookingResponse::from));
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID userId, UUID bookingId) {
        return BookingResponse.from(findBookingForUser(userId, bookingId));
    }

    @Transactional
    public BookingResponse cancelBooking(UUID userId, UUID bookingId) {
        Booking booking = findBookingForUser(userId, bookingId);

        // The state machine decides this. CANCELLED -> CANCELLED and FAILED ->
        // CANCELLED are both refused, and one message covers both because it
        // names the status that blocked the move.
        if (!booking.canTransitionTo(BookingStatus.CANCELLED)) {
            throw new InvalidBookingStateException(
                    "A booking with status " + booking.getStatus() + " cannot be cancelled.");
        }

        booking.transitionTo(BookingStatus.CANCELLED);
        log.info("Booking cancelled id={} user={}", booking.getId(), userId);
        return BookingResponse.from(booking);
    }

    /**
     * The only way a booking is loaded for the API.
     *
     * <p>Scoping the query by user id means someone else's booking id produces
     * exactly the same empty result as an id that was never issued, so we always
     * answer 404. Returning 403 here would confirm the id exists and let anyone
     * enumerate the customer base.
     */
    private Booking findBookingForUser(UUID userId, UUID bookingId) {
        return bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id " + bookingId));
    }
}