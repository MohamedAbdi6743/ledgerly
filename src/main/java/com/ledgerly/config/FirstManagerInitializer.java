package com.ledgerly.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.ledgerly.service.UserService;

/**
 * Creates the first manager at startup from environment settings (LEDGERLY_ADMIN_EMAIL,
 * LEDGERLY_ADMIN_PASSWORD, LEDGERLY_ADMIN_NAME). Every other account is created by staff,
 * so without this nobody could log in. The password never appears in code or in Git.
 */
@Component
public class FirstManagerInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(FirstManagerInitializer.class);
	private static final int MIN_PASSWORD_LENGTH = 12;

	private final UserService userService;
	private final String email;
	private final String password;
	private final String fullName;

	public FirstManagerInitializer(UserService userService,
			@Value("${ledgerly.admin.email:}") String email,
			@Value("${ledgerly.admin.password:}") String password,
			@Value("${ledgerly.admin.name:Branch Manager}") String fullName) {
		this.userService = userService;
		this.email = email;
		this.password = password;
		this.fullName = fullName;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (email.isBlank()) {
			log.info("LEDGERLY_ADMIN_EMAIL is not set, so no first manager was created.");
			return;
		}
		if (userService.emailExists(email)) {
			return;
		}
		// Refuse to start with a weak admin password rather than create one quietly.
		if (password.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException(
					"LEDGERLY_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters.");
		}
		userService.createInitialManager(fullName, email, password);
		log.info("Created the first manager account from settings.");
	}

}
