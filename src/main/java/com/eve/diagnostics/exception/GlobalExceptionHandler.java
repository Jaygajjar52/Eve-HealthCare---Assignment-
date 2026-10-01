package com.eve.diagnostics.exception;

import com.eve.diagnostics.dto.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Turns every exception into the single {@link ApiError} JSON shape.
 *
 * <p>Two rules apply to everything below: never send a stack trace or a SQL
 * message to the client, and never let one exception type silently fall through
 * to Spring's default error body, which has a different shape.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} matters more than it
 * looks. Spring throws its own exceptions for a lot of ordinary situations -
 * 404 for an unmapped path, 405 for the wrong HTTP method, 415 for the wrong
 * Content-Type, 400 for an unreadable body. Without this base class the
 * catch-all handler below would answer all of them with 500, telling the client
 * the server broke when in fact the request was simply wrong.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Single conversion point for every exception Spring MVC raises itself.
     * The status has already been decided by Spring at this point; we only
     * replace the body with our own shape.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {

        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String path = pathOf(request);

        if (status.is5xxServerError()) {
            log.error("Server error on {}: {}", path, ex.getMessage(), ex);
        } else {
            log.info("Rejected {} -> {}: {}", path, status.value(), ex.getMessage());
        }

        List<ApiError.FieldError> fieldErrors = fieldErrorsOf(ex);
        ApiError error = ApiError.of(status, messageFor(ex, status), path, fieldErrors);
        return new ResponseEntity<>(error, headers, statusCode);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex,
                                                        HttpServletRequest request) {
        // The message is deliberately generic: see AuthService.login.
        return json(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        return json(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex,
                                                   HttpServletRequest request) {
        log.info("Not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return json(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(WebhookSignatureException.class)
    public ResponseEntity<ApiError> handleWebhookSignature(WebhookSignatureException ex,
                                                           HttpServletRequest request) {
        // Nothing was read or written before this check, so there is no risk of a
        // forged request having had any effect.
        return json(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(WebhookValidationException.class)
    public ResponseEntity<ApiError> handleWebhookValidation(WebhookValidationException ex,
                                                            HttpServletRequest request) {
        log.info("Webhook validation failed on {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage(),
                        request.getRequestURI(), ex.getFieldErrors()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex,
                                                     HttpServletRequest request) {
        log.info("Bad request on {}: {}", request.getRequestURI(), ex.getMessage());
        return json(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Lost an optimistic-locking race on Booking.version: another request read
     * the same booking first and got its UPDATE in before ours. Nothing was
     * half-written, so the client is safe to simply try again.
     *
     * <p>This is caught here rather than inside the service because Hibernate
     * raises it when the transaction commits, which happens after the service
     * method has already returned.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                         HttpServletRequest request) {
        log.info("Optimistic lock conflict on {} - telling the client to retry", request.getRequestURI());
        return json(HttpStatus.CONFLICT, "This booking was changed by another request. Please retry.", request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        log.info("Conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return json(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * A unique constraint rejected a row, normally because two requests raced.
     *
     * <p>The services check first, for example {@code existsByEmail}, but a check
     * followed by an insert is a race: two simultaneous signups can both pass the
     * check and the second INSERT then loses. The database is what actually
     * decides, and it rejects the loser, which must be a 409 rather than the 500
     * this would otherwise become.
     *
     * <p>The message is deliberately fixed. The underlying message names the
     * table and constraint, which is exactly the detail a client should not get.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                  HttpServletRequest request) {
        log.info("Constraint violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return json(HttpStatus.CONFLICT,
                "That request conflicts with data that already exists.", request);
    }

    /** Anything unforeseen. The detail stays in the log, never in the response. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Please try again later.", request);
    }

    private ResponseEntity<ApiError> json(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status, message, request.getRequestURI()));
    }

    private String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest
                ? servletWebRequest.getRequest().getRequestURI()
                : "unknown-path";
    }

    private List<ApiError.FieldError> fieldErrorsOf(Exception ex) {
        if (!(ex instanceof MethodArgumentNotValidException validationException)) {
            return null;
        }
        return validationException.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
    }

    /**
     * A message the caller can actually act on.
     *
     * <p>Never the exception's own message for these: Spring's messages are
     * written for developers and quote class names and parameter types, which is
     * noise for a client and a small amount of information about our internals.
     */
    private String messageFor(Exception ex, HttpStatus status) {
        if (ex instanceof MethodArgumentNotValidException) {
            return "Validation failed.";
        }
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            // A malformed UUID in the path, or ?status=NOT_A_STATUS.
            return "'" + mismatch.getName() + "' has an invalid value. Expected "
                    + mismatch.getRequiredType().getSimpleName() + ".";
        }
        if (ex instanceof MissingServletRequestParameterException missing) {
            return "Required parameter '" + missing.getParameterName() + "' is missing.";
        }
        if (ex instanceof HttpMessageNotReadableException) {
            // Covers malformed JSON, a value of the wrong JSON type, and an
            // unrecognised enum constant such as "status": "REFUNDED".
            return "Request body is missing or not valid JSON.";
        }
        return switch (status) {
            case NOT_FOUND -> "The requested resource was not found.";
            case METHOD_NOT_ALLOWED -> "This endpoint does not support that HTTP method.";
            case UNSUPPORTED_MEDIA_TYPE -> "Content-Type must be application/json.";
            case BAD_REQUEST -> "The request could not be understood.";
            default -> status.getReasonPhrase();
        };
    }
}