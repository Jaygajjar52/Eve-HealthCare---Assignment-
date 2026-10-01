package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Login, and the duplicate check during signup. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}