package com.ledgerly.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.TransactionResponse;
import com.ledgerly.dto.TransferRequest;
import com.ledgerly.service.TransferService;

import jakarta.validation.Valid;

/** Any logged-in user can call this; TransferService decides which accounts they may use. */
@RestController
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	@PostMapping("/api/transfers")
	public ResponseEntity<TransactionResponse> transfer(@Valid @RequestBody TransferRequest request,
			@RequestHeader(name = CashController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			Authentication authentication) {
		return CashController.created(transferService.transfer(request, idempotencyKey, authentication.getName()));
	}

}
