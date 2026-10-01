package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.Booking;
import com.eve.diagnostics.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * The entity graph matters here. BookingResponse includes the centre and
     * test names, and both are LAZY associations, so without this every booking
     * on a page would trigger two extra queries - 1 + 2N. Fetching them in the
     * same statement keeps a page of 20 bookings at a fixed two queries (one for
     * the rows, one for the page count).
     */
    @EntityGraph(attributePaths = {"centre", "test"})
    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"centre", "test"})
    Page<Booking> findByUserIdAndStatus(UUID userId, BookingStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"centre", "test"})
    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);
}