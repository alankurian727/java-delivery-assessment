package com.abnamro.delivery.order.controller;

import com.abnamro.delivery.order.api.model.OrderStatus;
import com.abnamro.delivery.order.api.model.Problem;
import com.abnamro.delivery.order.api.model.ProblemViolationsInner;
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
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    private static final Map<String, String> HEADER_NAMES =
            Map.of("idempotencyKey", "Idempotency-Key", "xCorrelationID", "X-Correlation-ID");

    /** Bean Validation errors on the request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> handleInvalidBody(MethodArgumentNotValidException ex) {
        Problem problem = validationProblem();
        ex.getBindingResult().getFieldErrors().forEach(error -> problem.addViolationsItem(toViolation(error)));
        return respond(logInvalidRequest(problem, "validation_failed"));
    }

    /** Invalid headers: the generated interface is @Validated, so Spring's method validation checks them. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException ex) {
        Problem problem = validationProblem();
        ex.getConstraintViolations().forEach(violation -> problem.addViolationsItem(
                new ProblemViolationsInner(headerName(lastNodeName(violation)), violation.getMessage())));
        return respond(logInvalidRequest(problem, "validation_failed"));
    }

    /** The required Idempotency-Key header is missing. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Problem> handleMissingHeader(MissingRequestHeaderException ex) {
        Problem problem = validationProblem();
        problem.addViolationsItem(new ProblemViolationsInner(ex.getHeaderName(), "must not be null"));
        return respond(logInvalidRequest(problem, "missing_header"));
    }

    /** An orderId that is not a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Problem problem = validationProblem();
        problem.addViolationsItem(new ProblemViolationsInner(ex.getName(), "has an invalid format"));
        return respond(logInvalidRequest(problem, "invalid_parameter"));
    }

    /** Malformed JSON, wrong JSON types, fractional numbers or an unknown enum value. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Problem problem = validationProblem();
        problem.setDetail("The request body is not valid JSON for this operation.");
        return respond(logInvalidRequest(problem, "unreadable_body"));
    }

    @ExceptionHandler(DeliveryRejectedException.class)
    ResponseEntity<Problem> handleRejected(DeliveryRejectedException ex) {
        Problem problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Delivery is not eligible", ex.getRejectionCode());
        problem.setOrderStatus(OrderStatus.REJECTED);
        return respond(problem);
    }

    @ExceptionHandler(PricingUnavailableException.class)
    ResponseEntity<Problem> handlePricingUnavailable(PricingUnavailableException ex) {
        Problem problem = problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Pricing temporarily unavailable or invalid upstream response", "PRICING_UNAVAILABLE");
        problem.setOrderStatus(OrderStatus.PRICING_UNAVAILABLE);
        return respondRetryAfter(problem, 5);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Problem> handleConflict(IdempotencyConflictException ex) {
        return respond(problem(HttpStatus.CONFLICT,
                "Idempotency-Key already used for a different request", "IDEMPOTENCY_CONFLICT"));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Problem> handleNotFound(OrderNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, "Order not found", "ORDER_NOT_FOUND"));
    }

    /** Wrong method, content type, Accept header or path: keep Spring's status and standard headers (such as Allow). */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            NoResourceFoundException.class})
    ResponseEntity<Problem> handleUnsupportedRequest(Exception ex) {
        ErrorResponse errorResponse = (ErrorResponse) ex;
        HttpStatus httpStatus = HttpStatus.valueOf(errorResponse.getStatusCode().value());
        Problem problem = problem(httpStatus, httpStatus.getReasonPhrase(), REQUEST_NOT_SUPPORTED);
        return ResponseEntity.status(httpStatus)
                .headers(errorResponse.getHeaders())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handleUnexpected(Exception ex) {
        log.atError().setMessage("Unexpected error while handling a delivery order request")
                .addKeyValue("event", "order.failed")
                .setCause(ex)
                .log();
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", INTERNAL_ERROR));
    }

    private static Problem validationProblem() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", VALIDATION_FAILED);
    }

    private static Problem problem(HttpStatus status, String title, String code) {
        Problem problem = new Problem("about:blank", title, status.value(), code);
        problem.setCorrelationId(MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }

    /** Logs the field names of an invalid request only, never the rejected values. */
    private static Problem logInvalidRequest(Problem problem, String reason) {
        List<String> fields = problem.getViolations().stream().map(ProblemViolationsInner::getField).sorted().toList();
        log.atWarn().setMessage("Invalid delivery order request")
                .addKeyValue("event", "order.request_invalid")
                .addKeyValue("reason", reason)
                .addKeyValue("fields", fields)
                .log();
        return problem;
    }

    private static ProblemViolationsInner toViolation(FieldError error) {
        return new ProblemViolationsInner(error.getField(), error.getDefaultMessage());
    }

    private static String lastNodeName(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }

    private static String headerName(String parameterName) {
        return HEADER_NAMES.getOrDefault(parameterName, parameterName);
    }

    private static ResponseEntity<Problem> respond(Problem problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ResponseEntity<Problem> respondRetryAfter(Problem problem, int seconds) {
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
