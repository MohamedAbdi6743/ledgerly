package com.ledgerly.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.exception.BadRequestException;
import com.ledgerly.exception.IdempotencyConflictException;
import com.ledgerly.repository.JournalTransactionRepository;

/**
 * Makes retries safe. Every money request carries an Idempotency-Key (a random id the page
 * creates when the form opens). The key is saved with the transaction, along with a
 * fingerprint of what was asked for.
 *
 *  - Same key, same request: send back the original result, move no money.
 *  - Same key, different request: refuse with 422.
 *
 * The database backs this up with UNIQUE (created_by, idempotency_key).
 */
@Service
public class IdempotencyService {

	private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9-]{8,64}");

	private final JournalTransactionRepository journalTransactionRepository;

	public IdempotencyService(JournalTransactionRepository journalTransactionRepository) {
		this.journalTransactionRepository = journalTransactionRepository;
	}

	public void requireValidKey(String key) {
		if (key == null || key.isBlank()) {
			throw new BadRequestException("The Idempotency-Key header is required for requests that move money.");
		}
		if (!KEY_FORMAT.matcher(key).matches()) {
			throw new BadRequestException("Idempotency-Key must be 8 to 64 letters, digits or dashes.");
		}
	}

	/** SHA-256 of the request's parts. Two requests with the same parts get the same fingerprint. */
	public String fingerprint(String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			// A separator that can't appear in the parts, so ("ab","c") and ("a","bc") differ.
			byte[] hash = digest.digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available in Java", e);
		}
	}

	/** The earlier transaction for this key, if the request is a retry. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<JournalTransaction> findPrevious(AppUser user, String key, String fingerprint) {
		Optional<JournalTransaction> previous = journalTransactionRepository.findByCreatedByIdAndIdempotencyKey(user.getId(), key);
		if (previous.isPresent() && !previous.get().getRequestFingerprint().equals(fingerprint)) {
			throw new IdempotencyConflictException(
					"This Idempotency-Key was already used for a different request. Use a new key for a new request.");
		}
		return previous;
	}

}
