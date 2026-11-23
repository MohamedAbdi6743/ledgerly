package com.ledgerly.exception;

/** The user still has a temporary password and must change it before doing anything else. Becomes 403. */
public class PasswordChangeRequiredException extends RuntimeException {

	public PasswordChangeRequiredException() {
		super("Please change your temporary password first.");
	}

}
