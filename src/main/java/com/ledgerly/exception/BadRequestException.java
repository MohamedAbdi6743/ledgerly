package com.ledgerly.exception;

/** The request's values don't make sense (e.g. wrong current password). Becomes 400. */
public class BadRequestException extends RuntimeException {

	public BadRequestException(String message) {
		super(message);
	}

}
