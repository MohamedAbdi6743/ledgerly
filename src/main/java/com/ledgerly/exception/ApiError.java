package com.ledgerly.exception;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * The JSON body of every error, so the frontend handles all errors the same way.
 * Example: { "status": 409, "error": "Conflict", "message": "...", "fieldErrors": {}, "timestamp": "..." }
 */
public record ApiError(int status, String error, String message, Map<String, String> fieldErrors, Instant timestamp) {

	public static ApiError of(HttpStatusCode statusCode, String message) {
		return withFieldErrors(statusCode, message, Map.of());
	}

	public static ApiError withFieldErrors(HttpStatusCode statusCode, String message, Map<String, String> fieldErrors) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		String reason = (status != null) ? status.getReasonPhrase() : "Error";
		return new ApiError(statusCode.value(), reason, message, fieldErrors, Instant.now());
	}

}
