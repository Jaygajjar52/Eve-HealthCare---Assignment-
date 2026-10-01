package com.eve.diagnostics.security;

import com.eve.diagnostics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads a user by email, which is what we use as the login "username". */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email)
                .map(AppUserDetails::new)
                // Spring Security hides this behind BadCredentialsException by default,
                // so an unknown email and a wrong password look identical from outside.
                .orElseThrow(() -> new UsernameNotFoundException("No account found for that email"));
    }
}