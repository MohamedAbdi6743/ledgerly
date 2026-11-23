package com.ledgerly.dto;

import com.ledgerly.entity.AccountStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A reason is required, because freezing someone's money is something an auditor will ask about. */
public record ChangeAccountStatusRequest(
		@NotNull AccountStatus status,
		@NotBlank @Size(max = 200) String reason) {
}
