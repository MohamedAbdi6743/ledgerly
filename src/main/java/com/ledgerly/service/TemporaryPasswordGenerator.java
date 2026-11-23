package com.ledgerly.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Makes one-time passwords for new accounts. Uses SecureRandom (unpredictable, unlike
 * java.util.Random) and leaves out characters that are easy to misread aloud, like 0/O and 1/l/I.
 */
@Component
public class TemporaryPasswordGenerator {

	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
	private static final int LENGTH = 14;

	private final SecureRandom random = new SecureRandom();

	public String generate() {
		StringBuilder password = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return password.toString();
	}

}
