package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    Page<Booking> findByUserIdAndStatus(UUID userId, BookingStatus status, Pageable pageable);

    /**
     * The only way a booking is ever read for the API. Scoping the query by user
     * id means "not yours" and "does not exist" produce the same empty result,
     * so another user's booking id cannot be probed: we answer 404 either way.
     */
    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);
}