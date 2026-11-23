package com.ledgerly.exception;

/** The requested record doesn't exist (or the caller isn't allowed to know it exists). Becomes 404. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
