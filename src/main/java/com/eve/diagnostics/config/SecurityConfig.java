package com.eve.diagnostics.config;

import com.eve.diagnostics.security.JwtAuthFilter;
import com.eve.diagnostics.security.RestAccessDeniedHandler;
import com.eve.diagnostics.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security.
 *
 * <p>CSRF is disabled on purpose. CSRF attacks work by making the browser
 * silently attach credentials the user already has - which is only a problem
 * when credentials ride along automatically in a cookie. Here the token is in
 * the Authorization header, which a cross-site form or image cannot set, so
 * there is nothing for an attacker to ride on. Re-enabling CSRF without cookie
 * auth would only add noise.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Public endpoints. Everything not listed here needs a valid token. */
    private static final String[] PUBLIC_PATHS = {
            "/",
            "/auth/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private final JwtAuthFilter jwtAuthFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Browsing the catalogue is public; anything else on these
                        // paths still needs a token.
                        .requestMatchers(HttpMethod.GET, "/centres/**", "/tests/**").permitAll()
                        // Public route, but still protected by the X-Signature HMAC
                        // check inside PaymentController. Both spellings are listed
                        // because Boot disables trailing-slash matching, so without
                        // this a client posting to /payments/webhook would get a 401
                        // instead of reaching the endpoint.
                        .requestMatchers(HttpMethod.POST, "/payments/webhook", "/payments/webhook/").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** Used by AuthService for both signup hashing and login verification. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}