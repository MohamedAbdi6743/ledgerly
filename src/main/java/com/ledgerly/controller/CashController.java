package com.ledgerly.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.CashRequest;
import com.ledgerly.dto.TransactionResponse;
import com.ledgerly.service.PaymentService;
import com.ledgerly.service.PaymentService.PaymentResult;
import com.ledgerly.service.TransactionService;

import jakarta.validation.Valid;

/**
 * Cash at the counter (tellers and managers), and looking up a transaction.
 * The Idempotency-Key header is optional here only so the service can answer a missing one
 * with our own clear 400 message.
 */
@RestController
public class CashController {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	static final String REPLAYED = "Idempotent-Replayed";

	private final PaymentService paymentService;
	private final TransactionService transactionService;

	public CashController(PaymentService paymentService, TransactionService transactionService) {
		this.paymentService = paymentService;
		this.transactionService = transactionService;
	}

	@PostMapping("/api/deposits")
	public ResponseEntity<TransactionResponse> deposit(@Valid @RequestBody CashRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			Authentication authentication) {
		return created(paymentService.deposit(request, idempotencyKey, authentication.getName()));
	}

	@PostMapping("/api/withdrawals")
	public ResponseEntity<TransactionResponse> withdraw(@Valid @RequestBody CashRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			Authentication authentication) {
		return created(paymentService.withdraw(request, idempotencyKey, authentication.getName()));
	}

	@GetMapping("/api/transactions/{reference}")
	public TransactionResponse getTransaction(@PathVariable String reference, Authentication authentication) {
		return transactionService.get(reference, authentication.getName());
	}

	/** A retry gets the same status and body as the first try, plus a header saying it was a replay. */
	private static ResponseEntity<TransactionResponse> created(PaymentResult result) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
		if (result.replayed()) {
			response.header(REPLAYED, "true");
		}
		return response.body(result.transaction());
	}

}
