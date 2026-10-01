package com.eve.diagnostics.security;

import com.eve.diagnostics.entity.User;
import com.eve.diagnostics.enums.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated user, as Spring Security sees them.
 *
 * <p>We use our own type instead of Spring's built-in UserDetails so controllers
 * can get the user's id with {@code @AuthenticationPrincipal AppUserDetails}
 * instead of parsing the JWT again or doing another database lookup.
 *
 * <p>Email is the username throughout this API.
 */
@Getter
public class AppUserDetails implements UserDetails {

    private final UUID userId;
    private final String email;
    private final Role role;

    /** The BCrypt hash. Only ever handed to the password encoder, never logged. */
    private final String passwordHash;

    public AppUserDetails(User user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.role = user.getRole();
        this.passwordHash = user.getPasswordHash();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}