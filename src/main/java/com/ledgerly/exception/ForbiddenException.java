package com.ledgerly.exception;

/**
 * Logged in with the right role, but this particular action isn't allowed for this person
 * (e.g. approving your own request). Becomes 403 with a message saying why.
 */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}

}
