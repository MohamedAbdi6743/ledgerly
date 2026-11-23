package com.ledgerly.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns any exception from a controller or service into the same JSON error shape. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
		}
		return respond(ApiError.withFieldErrors(HttpStatus.BAD_REQUEST, "Some fields are invalid.", fieldErrors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
		return respond(ApiError.of(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON."));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return respond(ApiError.of(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'."));
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex) {
		return respond(ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage()));
	}

	@ExceptionHandler(PasswordChangeRequiredException.class)
	public ResponseEntity<ApiError> handlePasswordChangeRequired(PasswordChangeRequiredException ex) {
		return respond(ApiError.of(HttpStatus.FORBIDDEN, ex.getMessage()));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
		return respond(ApiError.of(HttpStatus.FORBIDDEN, "You don't have permission to do this."));
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
		return respond(ApiError.of(HttpStatus.NOT_FOUND, ex.getMessage()));
	}

	@ExceptionHandler(BusinessRuleException.class)
	public ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException ex) {
		return respond(ApiError.of(HttpStatus.CONFLICT, ex.getMessage()));
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex) {
		return respond(ApiError.of(HttpStatusCode.valueOf(422), ex.getMessage()));
	}

	/** A database constraint refused the change. The real message is logged, not shown (it names tables). */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
		log.warn("Database constraint violated: {}", ex.getMostSpecificCause().getMessage());
		return respond(ApiError.of(HttpStatus.CONFLICT, "This change conflicts with existing data."));
	}

	/** Anything else. Spring's own errors (unknown URL, wrong method, missing header) keep their status. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleEverythingElse(Exception ex) {
		if (ex instanceof ErrorResponse springError) {
			return respond(ApiError.of(springError.getStatusCode(), springError.getBody().getDetail()));
		}
		log.error("Unexpected error", ex);
		return respond(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side."));
	}

	private ResponseEntity<ApiError> respond(ApiError error) {
		return ResponseEntity.status(error.status()).body(error);
	}

}
