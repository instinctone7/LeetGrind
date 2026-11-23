package com.leetgrind.platform;

import java.io.IOException;
import java.sql.SQLException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice
public class ApiErrors {
    private final JsonMapper json;

    ApiErrors(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, Failure failure) throws IOException {
        response.setStatus(failure.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body(request, failure));
    }

    @ExceptionHandler(Failure.class)
    ResponseEntity<ErrorBody> failure(HttpServletRequest request, Failure failure) {
        return ResponseEntity.status(failure.status()).body(body(request, failure));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalidRequest(HttpServletRequest request) {
        return failure(request, new Failure(HttpStatus.BAD_REQUEST, "invalid_request", "Check the submitted fields."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> conflict(HttpServletRequest request, DataIntegrityViolationException exception) {
        if (exception.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 1062) {
            return failure(request, new Failure(HttpStatus.CONFLICT, "account_conflict",
                    "An account already uses that email."));
        }
        LoggerFactory.getLogger(ApiErrors.class).error("Database integrity check failed");
        return failure(request, new Failure(HttpStatus.INTERNAL_SERVER_ERROR, "server_error",
                "The request could not be completed."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedType(HttpServletRequest request) {
        return failure(request, new Failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Send JSON."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedMethod(HttpServletRequest request) {
        return failure(request, new Failure(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method is not allowed."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> missing(HttpServletRequest request) {
        return failure(request, new Failure(HttpStatus.NOT_FOUND, "not_found", "That resource could not be found."));
    }

    private static ErrorBody body(HttpServletRequest request, Failure failure) {
        return new ErrorBody(failure.code(), failure.getMessage(), RequestCorrelation.id(request));
    }

    record ErrorBody(String code, String message, String correlationId) {}

    public static final class Failure extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public Failure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
