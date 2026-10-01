package com.eve.diagnostics.service;

import com.eve.diagnostics.dto.request.LoginRequest;
import com.eve.diagnostics.dto.request.SignupRequest;
import com.eve.diagnostics.dto.response.AuthResponse;
import com.eve.diagnostics.entity.User;
import com.eve.diagnostics.enums.Role;
import com.eve.diagnostics.exception.ConflictException;
import com.eve.diagnostics.repository.UserRepository;
import com.eve.diagnostics.security.AppUserDetails;
import com.eve.diagnostics.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String email = normaliseEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with that email already exists.");
        }

        User user = userRepository.save(User.builder()
                .name(request.name().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .build());

        log.info("Registered new user id={} email={}", user.getId(), user.getEmail());
        return AuthResponse.bearer(jwtService.generateToken(user), jwtService.getExpiresInSeconds());
    }

    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        try {
            // The AuthenticationManager loads the user and checks the BCrypt hash
            // for us. It throws the same BadCredentialsException for an unknown
            // email and a wrong password, so the response cannot be used to find
            // out which addresses are registered.
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));

            AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
            log.info("Login succeeded for user id={} email={}", principal.getUserId(), principal.getEmail());

            return AuthResponse.bearer(
                    jwtService.generateToken(principal.getEmail(), principal.getUserId()),
                    jwtService.getExpiresInSeconds());
        } catch (BadCredentialsException ex) {
            log.info("Failed login attempt for email={}", email);
            throw new BadCredentialsException("Invalid email or password.");
        }
    }

    /**
     * Lowercases and trims so Ada@Example.com and ada@example.com can never
     * become two accounts. The database has a matching CHECK constraint, so this
     * is enforced on both sides.
     */
    private String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}