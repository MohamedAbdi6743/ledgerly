package com.ledgerly.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TemporaryPasswordGeneratorTest {

	private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();

	@Test
	void passwordsAreFourteenEasyToReadCharacters() {
		String password = generator.generate();

		assertThat(password).hasSize(14);
		// No 0, O, 1, l or I: easy to read out loud or copy from a screen.
		assertThat(password).matches("[A-HJ-NP-Za-km-z2-9]{14}");
	}

	@Test
	void passwordsDontRepeat() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			seen.add(generator.generate());
		}

		assertThat(seen).hasSize(1000);
	}

}
