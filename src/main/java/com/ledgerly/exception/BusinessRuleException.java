package com.ledgerly.exception;

/** The request is well-formed but breaks a rule (e.g. "email already registered"). Becomes 409. */
public class BusinessRuleException extends RuntimeException {

	public BusinessRuleException(String message) {
		super(message);
	}

}
