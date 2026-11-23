package com.ledgerly.exception;

/**
 * An Idempotency-Key was reused for a DIFFERENT request (other amount, account or type).
 * That's a client bug or tampering, so nothing is done. Becomes 422.
 */
public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException(String message) {
		super(message);
	}

}
