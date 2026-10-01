package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.DiagnosticCentre;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DiagnosticCentreRepository extends JpaRepository<DiagnosticCentre, UUID> {

    /** GET /centres?city=Bengaluru. Matches the whole city name, ignoring case. */
    Page<DiagnosticCentre> findByCityIgnoreCase(String city, Pageable pageable);
}