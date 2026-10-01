package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.CentreTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CentreTestRepository extends JpaRepository<CentreTest, UUID> {

    /**
     * Booking creation: this centre must actually offer this test.
     *
     * <p>The entity graph fetches the centre and the test in the same statement.
     * BookingService needs both to fill in the booking's denormalised centre_id
     * and test_id, and without this it would be three queries instead of one.
     */
    @EntityGraph(attributePaths = {"centre", "test"})
    Optional<CentreTest> findByCentreIdAndTestId(UUID centreId, UUID testId);

    /** Attaching a test twice to the same centre must fail with 409. */
    boolean existsByCentreIdAndTestId(UUID centreId, UUID testId);

    /**
     * Paginated, for GET /centres/{id}/tests. The graph matters here because
     * CentreTestResponse includes the test's name; without it every row on the
     * page would trigger its own diagnostic_tests query.
     */
    @EntityGraph(attributePaths = {"test"})
    Page<CentreTest> findByCentreId(UUID centreId, Pageable pageable);

    /**
     * Unpaginated, for GET /centres/{id} which returns the centre together with
     * all of its tests. A centre offers a handful of tests, so one extra query is
     * cheaper than paging inside a nested object, and it avoids an N+1 query per
     * centre when listing many centres.
     */
    @EntityGraph(attributePaths = {"test"})
    List<CentreTest> findByCentreId(UUID centreId);
}