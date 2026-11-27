package com.abnamro.delivery.pricing.controller;

import com.abnamro.delivery.pricing.api.model.Problem;
import com.abnamro.delivery.pricing.api.model.Violation;
import com.abnamro.delivery.pricing.configuration.CorrelationIdFilter;
import com.abnamro.delivery.pricing.exception.InvalidPostalCodeException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every error into an RFC 9457 Problem Details response.
 * Responses never echo request values, stack traces or internal details.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String REQUEST_NOT_SUPPORTED = "REQUEST_NOT_SUPPORTED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * Every invalid request: body fields, the X-Correlation-ID header, a body that is not valid JSON, or a postal code
     * the domain rejects (the contract's pattern normally rejects it first).
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class, // a body field breaks its contract rule (@Valid on the body)
            ConstraintViolationException.class,    // the X-Correlation-ID header breaks its rule
            HttpMessageNotReadableException.class, // the body is not valid JSON, has a wrong type or unknown enum
            InvalidPostalCodeException.class})     // the domain rejects a postal code the contract let through
    ResponseEntity<Problem> handleInvalidRequest(Exception ex) {
        Problem problem = problem(HttpStatus.BAD_REQUEST, "Invalid evaluation request", VALIDATION_FAILED);
        String reason = switch (ex) {
            case MethodArgumentNotValidException e -> {
                e.getFieldErrors().forEach(error ->
                        problem.addViolationsItem(new Violation(error.getField(), error.getDefaultMessage())));
                yield "invalid_body";
            }
            case ConstraintViolationException e -> {
                e.getConstraintViolations().forEach(violation ->
                        problem.addViolationsItem(new Violation(headerName(violation), violation.getMessage())));
                yield "invalid_header";
            }
            case InvalidPostalCodeException e -> {
                problem.setDetail("A postal code is not a valid Dutch postal code.");
                yield "invalid_postal_code";
            }
            default -> {
                problem.setDetail("The request body is not valid JSON for this operation.");
                yield "unreadable_body";
            }
        };
        return badRequest(problem, reason);
    }

    /**
     * Spring's own request errors (wrong method, content type, Accept header or path) keep their status and standard
     * headers, such as Allow. Anything else is a bug: logged, and answered with a generic 500.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handleOther(Exception ex) {
        // Spring's request errors implement ErrorResponse: 405 wrong method, 415 wrong Content-Type,
        // 406 unsupported Accept header, 404 unknown path.
        if (ex instanceof ErrorResponse springError) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            return respond(problem(status, status.getReasonPhrase(), REQUEST_NOT_SUPPORTED), springError.getHeaders());
        }
        log.atError().setMessage("Unexpected error while evaluating pricing")
                .addKeyValue("event", "pricing.failed")
                .setCause(ex)
                .log();
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", INTERNAL_ERROR), new HttpHeaders());
    }

    /**
     * Logs one event without request values (the correlation ID comes from the MDC) and returns the 400. Pricing's
     * only client validates requests first, so this usually means a client bug or contract drift.
     */
    private static ResponseEntity<Problem> badRequest(Problem problem, String reason) {
        log.atWarn().setMessage("Invalid pricing request")
                .addKeyValue("event", "pricing.request_invalid")
                .addKeyValue("reason", reason)
                .log();
        return respond(problem, new HttpHeaders());
    }

    private static Problem problem(HttpStatus status, String title, String code) {
        Problem problem = new Problem("about:blank", title, status.value(), code);
        problem.setCorrelationId(MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }

    private static ResponseEntity<Problem> respond(Problem problem, HttpHeaders headers) {
        return ResponseEntity.status(problem.getStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** The last node of the violation's path is the method parameter; xCorrelationID is the X-Correlation-ID header. */
    private static String headerName(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return "xCorrelationID".equals(name) ? "X-Correlation-ID" : name;
    }
}
