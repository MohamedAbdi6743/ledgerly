package com.ledgerly.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.DecisionRequest;
import com.ledgerly.dto.PendingRequestResponse;
import com.ledgerly.dto.TransactionResponse;
import com.ledgerly.service.ApprovalService;
import com.ledgerly.service.ReversalService;

import jakarta.validation.Valid;

/** Manager-only actions on transactions (see SecurityConfig): the approval queue and reversals. */
@RestController
public class ApprovalController {

	private final ApprovalService approvalService;
	private final ReversalService reversalService;

	public ApprovalController(ApprovalService approvalService, ReversalService reversalService) {
		this.approvalService = approvalService;
		this.reversalService = reversalService;
	}

	@GetMapping("/api/approvals")
	public List<PendingRequestResponse> pending() {
		return approvalService.pending();
	}

	@PostMapping("/api/approvals/{reference}/approve")
	public TransactionResponse approve(@PathVariable String reference, Authentication authentication) {
		return approvalService.approve(reference, authentication.getName());
	}

	@PostMapping("/api/approvals/{reference}/reject")
	public TransactionResponse reject(@PathVariable String reference, @Valid @RequestBody DecisionRequest request,
			Authentication authentication) {
		return approvalService.reject(reference, request.reason(), authentication.getName());
	}

	@PostMapping("/api/transactions/{reference}/reversal")
	public ResponseEntity<TransactionResponse> reverse(@PathVariable String reference,
			@Valid @RequestBody DecisionRequest request,
			@RequestHeader(name = CashController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			Authentication authentication) {
		return CashController.created(
				reversalService.reverse(reference, request.reason(), idempotencyKey, authentication.getName()));
	}

}
