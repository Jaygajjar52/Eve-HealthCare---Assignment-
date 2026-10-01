package com.eve.diagnostics.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts a request id into the logging context so every line printed while
 * handling a request can be tied back to it.
 *
 * <p>Without this, a single log line does not say which of several overlapping
 * requests produced it, which makes chasing a bug through concurrent requests
 * needlessly painful. application.yml already prints
 * {@code [%X{requestId:-no-request-id}]} in the console pattern.
 *
 * <p>Registered ahead of Spring Security's filter chain so even authentication
 * failures carry an id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    /**
     * A caller-supplied id is echoed back so a client can quote it in a support
     * request, but only after this check. Without it anyone could inject newlines
     * or fake log lines by putting arbitrary text in the header.
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        // Returned to the caller so a failing request can be traced end to end.
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat reuses threads, so leaving this behind would stamp the next
            // request, served by this thread, with the previous request's id.
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    }