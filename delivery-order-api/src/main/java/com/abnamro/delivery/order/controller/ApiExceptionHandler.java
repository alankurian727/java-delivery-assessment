package com.abnamro.delivery.order.controller;

import com.abnamro.delivery.order.api.model.OrderStatus;
import com.abnamro.delivery.order.api.model.Problem;
import com.abnamro.delivery.order.api.model.Violation;
import com.abnamro.delivery.order.configuration.CorrelationIdFilter;
import com.abnamro.delivery.order.exception.DeliveryRejectedException;
import com.abnamro.delivery.order.exception.IdempotencyConflictException;
import com.abnamro.delivery.order.exception.OrderNotFoundException;
import com.abnamro.delivery.order.exception.PricingUnavailableException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns every error into an RFC 9457 Problem Details response (ADR-004).
 * Responses never echo request values, stack traces or internal details.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String REQUEST_NOT_SUPPORTED = "REQUEST_NOT_SUPPORTED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // Generated parameter names of the validated headers, shown to clients as the header names.
    private static final Map<String, String> HEADER_NAMES =
            Map.of("idempotencyKey", "Idempotency-Key", "xCorrelationID", "X-Correlation-ID");

    /** Every invalid request: body fields, headers, a non-UUID orderId, or a body that is not valid JSON. */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<Problem> handleInvalidRequest(Exception ex) {
        Problem problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", VALIDATION_FAILED);
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
            case MissingRequestHeaderException e -> {
                problem.addViolationsItem(new Violation(e.getHeaderName(), "must not be null"));
                yield "missing_header";
            }
            case MethodArgumentTypeMismatchException e -> {
                problem.addViolationsItem(new Violation(e.getName(), "has an invalid format"));
                yield "invalid_parameter";
            }
            default -> {
                problem.setDetail("The request body is not valid JSON for this operation.");
                yield "unreadable_body";
            }
        };
        return badRequest(problem, reason);
    }

    @ExceptionHandler(DeliveryRejectedException.class)
    ResponseEntity<Problem> handleRejected(DeliveryRejectedException ex) {
        Problem problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Delivery is not eligible", ex.getRejectionCode());
        problem.setOrderStatus(OrderStatus.REJECTED);
        return respond(problem, new HttpHeaders());
    }

    @ExceptionHandler(PricingUnavailableException.class)
    ResponseEntity<Problem> handlePricingUnavailable(PricingUnavailableException ex) {
        Problem problem = problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Pricing temporarily unavailable or invalid upstream response", "PRICING_UNAVAILABLE");
        problem.setOrderStatus(OrderStatus.PRICING_UNAVAILABLE);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return respond(problem, headers);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Problem> handleConflict(IdempotencyConflictException ex) {
        return respond(problem(HttpStatus.CONFLICT,
                "Idempotency-Key already used for a different request", "IDEMPOTENCY_CONFLICT"), new HttpHeaders());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Problem> handleNotFound(OrderNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, "Order not found", "ORDER_NOT_FOUND"), new HttpHeaders());
    }

    /**
     * Spring's own request errors (wrong method, content type, Accept header or path) keep their status and standard
     * headers, such as Allow. Anything else is a bug: logged, and answered with a generic 500.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handleOther(Exception ex) {
        if (ex instanceof ErrorResponse springError) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            return respond(problem(status, status.getReasonPhrase(), REQUEST_NOT_SUPPORTED), springError.getHeaders());
        }
        log.atError().setMessage("Unexpected error while handling a delivery order request")
                .addKeyValue("event", "order.failed")
                .setCause(ex)
                .log();
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", INTERNAL_ERROR), new HttpHeaders());
    }

    /** Logs one event without request values (the correlation ID comes from the MDC) and returns the 400. */
    private static ResponseEntity<Problem> badRequest(Problem problem, String reason) {
        log.atWarn().setMessage("Invalid delivery order request")
                .addKeyValue("event", "order.request_invalid")
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

    /** The last node of the violation's path is the method parameter, for example idempotencyKey. */
    private static String headerName(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return HEADER_NAMES.getOrDefault(name, name);
    }
}
