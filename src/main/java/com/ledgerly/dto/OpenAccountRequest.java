package com.ledgerly.dto;

import com.ledgerly.entity.AccountType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** type must be CHEQUING or SAVINGS; the service refuses SYSTEM. */
public record OpenAccountRequest(
		@NotNull AccountType type,
		@NotBlank @Size(max = 60) String name) {
}
