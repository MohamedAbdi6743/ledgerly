package com.ledgerly.dto;

import java.time.Instant;

import com.ledgerly.entity.Account;
import com.ledgerly.entity.AccountStatus;
import com.ledgerly.entity.AccountType;

public record AccountResponse(String accountNumber, AccountType type, String name, String currency, String balance,
		AccountStatus status, Instant createdAt) {

	public static AccountResponse from(Account account) {
		return new AccountResponse(account.getAccountNumber(), account.getType(), account.getName(),
				account.getCurrency(), Money.toText(account.getBalance()), account.getStatus(), account.getCreatedAt());
	}

}
