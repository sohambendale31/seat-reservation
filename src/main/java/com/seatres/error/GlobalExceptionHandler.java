package com.seatres.error;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into a problem+json body. Extends ResponseEntityExceptionHandler so Boot's
 * own problem handler backs off and all Spring MVC exceptions land here too.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return respond(ex.code(), ex.detail(), ex.extensions(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
            WebRequest request) {
        List<Map<String, String>> errors = List.of(
                Map.of("field", ex.getName(), "message", "must be a valid value"));
        return respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.detail(),
                Map.of("errors", errors), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("unhandled.exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of("field", error.getField(), "message", messageOf(error.getDefaultMessage())));
        }
        ex.getBindingResult().getGlobalErrors().forEach(error -> errors.add(
                Map.of("field", error.getObjectName(), "message", messageOf(error.getDefaultMessage()))));
        return respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.detail(),
                Map.of("errors", errors), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, malformedDetail(ex), Map.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(ErrorCode.NOT_FOUND, request);
    }

    @Override
    protected ResponseEntity<Object> handleErrorResponseException(ErrorResponseException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(codeForStatus(ex.getStatusCode()), request);
    }

    /** Catch-all for the Spring MVC exceptions whose handlers are not overridden above. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(codeForStatus(status), request);
    }

    private ResponseEntity<Object> respond(ErrorCode code, WebRequest request) {
        return respond(code, code.detail(), Map.of(), request);
    }

    private ResponseEntity<Object> respond(ErrorCode code, String detail,
            Map<String, Object> extensions, WebRequest request) {
        ProblemDetail problem = Problems.of(code, detail, pathOf(request), extensions);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (code.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        if (code.retryable()) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(problem);
    }

    private static ErrorCode codeForStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

    /** Names the offending field but never echoes driver or parser internals. */
    private static String malformedDetail(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof UnrecognizedPropertyException cause) {
            return "Request body could not be parsed: unrecognized field '"
                    + cause.getPropertyName() + "'.";
        }
        return ErrorCode.MALFORMED_REQUEST.detail();
    }

    private static String messageOf(String defaultMessage) {
        return defaultMessage == null ? "is invalid" : defaultMessage;
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : null;
    }
}
