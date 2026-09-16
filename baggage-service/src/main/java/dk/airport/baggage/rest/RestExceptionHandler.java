package dk.airport.baggage.rest;

import dk.airport.baggage.domain.ApiException;
import dk.airport.baggage.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Maps exceptions of the REST API to RFC 9457 problem details ({@code application/problem+json}). Every answer
 * carries the extension member {@code code} with the same {@link ErrorCode} values the GraphQL API uses in
 * {@code errors[].extensions.code}, so a client can branch on one vocabulary regardless of the API style:
 *
 * <pre>
 *   400 VALIDATION_ERROR         request shape (Bean Validation, malformed JSON, bad path variable)
 *   404 NOT_FOUND                unknown tag / unknown booking
 *   409 INVALID_STATE, CONFLICT  booking not confirmed, duplicate data
 *   422 VALIDATION_ERROR, BAGGAGE_LIMIT_EXCEEDED   the request is well-formed but breaks a business rule
 *   500 INTERNAL_ERROR
 * </pre>
 * 401/403 are produced before the controller by Spring Security (see config/ProblemAuthHandlers). Only applies
 * to the REST controller - GraphQL keeps its own GraphQlExceptionResolver.
 */
@RestControllerAdvice(assignableTypes = BaggageRestController.class)
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail apiException(ApiException ex, HttpServletRequest request) {
        return problem(statusOf(ex.getCode()), ex.getCode(), ex.getMessage(), request);
    }

    /** Constraint on a path variable (class-level @Validated). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> lastPathSegment(v) + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, detail, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail dataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "The operation conflicts with existing data", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error in {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error", request);
    }

    /** Bean Validation on a @RequestBody: 400 with one line per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return handleExceptionInternal(ex, withCode(ex.getBody(), ErrorCode.VALIDATION_ERROR, detail), headers,
                status, request);
    }

    /** Built-in method validation (parameters without a class-level @Validated): same shape as above. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        String detail = ex.getAllErrors().stream()
                .map(e -> e.getDefaultMessage() == null ? "invalid value" : e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return handleExceptionInternal(ex, withCode(ex.getBody(), ErrorCode.VALIDATION_ERROR, detail), headers,
                status, request);
    }

    /**
     * Malformed JSON or an unknown enum value: 400 without echoing parser internals. Unlike the two exceptions
     * above, this one is not an {@code ErrorResponse}, so the ProblemDetail is built from scratch here.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = withCode(ProblemDetail.forStatus(status), ErrorCode.VALIDATION_ERROR,
                "Request body is not valid JSON for this endpoint (check field names, enum values and types)");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // ------------------------------------------------------------------ helpers

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String detail, HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code.name());
        problem.setInstance(URI.create(req.getRequestURI()));
        problem.setProperty("code", code.name());
        return problem;
    }

    private static ProblemDetail withCode(ProblemDetail body, ErrorCode code, String detail) {
        body.setTitle(code.name());
        body.setDetail(detail);
        body.setProperty("code", code.name());
        return body;
    }

    static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_STATE, CONFLICT -> HttpStatus.CONFLICT;
            case VALIDATION_ERROR, BAGGAGE_LIMIT_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String lastPathSegment(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
