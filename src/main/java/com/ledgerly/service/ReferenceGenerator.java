package com.ledgerly.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Transaction references like TX-7KQ2MZ4H, the number a member would read out over the phone.
 * Random rather than counting up, so a reference doesn't reveal how many transactions exist.
 * No 0/O or 1/I, which are easy to mix up when read aloud.
 */
@Component
public class ReferenceGenerator {

	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int LENGTH = 8;

	private final SecureRandom random = new SecureRandom();

	public String next() {
		StringBuilder reference = new StringBuilder("TX-");
		for (int i = 0; i < LENGTH; i++) {
			reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return reference.toString();
	}

}
