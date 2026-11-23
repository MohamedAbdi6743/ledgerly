package com.ledgerly.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a manager rejected a request, or why a transaction is being reversed. */
public record DecisionRequest(@NotBlank @Size(max = 140) String reason) {
}
