package com.abnamro.delivery.pricing.controller;

import com.abnamro.delivery.pricing.api.model.Problem;
import com.abnamro.delivery.pricing.api.model.ProblemViolationsInner;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** Bean Validation errors on the request body (missing fields, bad postcode, weight below 1). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> handleInvalidBody(MethodArgumentNotValidException ex) {
        Problem problem = validationProblem();
        ex.getBindingResult().getFieldErrors().forEach(error -> problem.addViolationsItem(toViolation(error)));
        return respond(problem);
    }

    /**
     * Invalid header. The generated interface is annotated with @Validated, so Spring's method
     * validation checks the X-Correlation-ID constraint and throws this exception.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException ex) {
        Problem problem = validationProblem();
        ex.getConstraintViolations().forEach(violation -> problem.addViolationsItem(
                new ProblemViolationsInner(headerOrParameterName(lastNodeName(violation)), violation.getMessage())));
        return respond(problem);
    }

    /** Malformed JSON, wrong JSON types, or an unknown deliveryType value. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Problem problem = validationProblem();
        problem.setDetail("The request body is not valid JSON for this operation.");
        return respond(problem);
    }

    /** Values that pass the schema but are rejected by the domain. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Problem> handleInvalidValue(IllegalArgumentException ex) {
        Problem problem = validationProblem();
        problem.setDetail(ex.getMessage());
        return respond(problem);
    }

    /** Wrong method, content type, Accept header or path: keep Spring's status instead of returning 500. */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            NoResourceFoundException.class})
    ResponseEntity<Problem> handleUnsupportedRequest(Exception ex) {
        HttpStatusCode status = ((ErrorResponse) ex).getStatusCode();
        HttpStatus httpStatus = HttpStatus.valueOf(status.value());
        return respond(problem(httpStatus, httpStatus.getReasonPhrase(), REQUEST_NOT_SUPPORTED));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handleUnexpected(Exception ex) {
        log.error("Unexpected error while evaluating pricing", ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", INTERNAL_ERROR));
    }

    private static Problem validationProblem() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid evaluation request", VALIDATION_FAILED);
    }

    private static Problem problem(HttpStatus status, String title, String code) {
        Problem problem = new Problem("about:blank", title, status.value(), code);
        problem.setCorrelationId(MDC.get("correlationId"));
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

    private static String headerOrParameterName(String parameterName) {
        return "xCorrelationID".equals(parameterName) ? "X-Correlation-ID" : parameterName;
    }

    private static ResponseEntity<Problem> respond(Problem problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
