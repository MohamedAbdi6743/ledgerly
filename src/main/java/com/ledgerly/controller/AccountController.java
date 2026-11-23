package com.ledgerly.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.AccountDetailResponse;
import com.ledgerly.dto.AccountResponse;
import com.ledgerly.dto.ChangeAccountStatusRequest;
import com.ledgerly.dto.OpenAccountRequest;
import com.ledgerly.service.AccountService;

import jakarta.validation.Valid;

/** Roles per URL are in SecurityConfig; "is this your account?" is checked in AccountService. */
@RestController
public class AccountController {

	private final AccountService accountService;

	public AccountController(AccountService accountService) {
		this.accountService = accountService;
	}

	@PostMapping("/api/members/{memberId}/accounts")
	@ResponseStatus(HttpStatus.CREATED)
	public AccountResponse openAccount(@PathVariable Long memberId, @Valid @RequestBody OpenAccountRequest request,
			Authentication authentication) {
		return accountService.openAccount(memberId, request.type(), request.name(), authentication.getName());
	}

	@GetMapping("/api/members/{memberId}/accounts")
	public List<AccountResponse> accountsOfMember(@PathVariable Long memberId) {
		return accountService.accountsOfMember(memberId);
	}

	@GetMapping("/api/me/accounts")
	public List<AccountResponse> myAccounts(Authentication authentication) {
		return accountService.myAccounts(authentication.getName());
	}

	@GetMapping("/api/accounts/{accountNumber}")
	public AccountDetailResponse getAccount(@PathVariable String accountNumber, Authentication authentication) {
		return accountService.getAccount(accountNumber, authentication.getName());
	}

	@PutMapping("/api/accounts/{accountNumber}/status")
	public AccountResponse changeStatus(@PathVariable String accountNumber,
			@Valid @RequestBody ChangeAccountStatusRequest request, Authentication authentication) {
		return accountService.changeStatus(accountNumber, request.status(), request.reason(), authentication.getName());
	}

}
