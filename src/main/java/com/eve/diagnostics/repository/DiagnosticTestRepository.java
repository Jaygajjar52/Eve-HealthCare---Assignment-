package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.DiagnosticTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DiagnosticTestRepository extends JpaRepository<DiagnosticTest, UUID> {

    /** GET /tests?name=lipid - optional search, so it has to cope with no name too. */
    Page<DiagnosticTest> findByNameContainingIgnoreCase(String name, Pageable pageable);
}