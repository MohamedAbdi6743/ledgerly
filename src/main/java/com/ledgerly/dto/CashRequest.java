package com.ledgerly.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A cash deposit or withdrawal at the counter. The amount arrives as a string ("200.00") and
 * Jackson turns it straight into a BigDecimal, so it never passes through a double.
 * Digits(fraction = 2) matters: PostgreSQL would silently round 10.005 to 10.01.
 */
public record CashRequest(
		@NotBlank String accountNumber,
		@NotNull
		@DecimalMin(value = "0.01", message = "must be at least 0.01")
		@DecimalMax(value = "1000000.00", message = "must be at most 1,000,000.00")
		@Digits(integer = 7, fraction = 2, message = "must have at most 2 decimal places")
		BigDecimal amount,
		@Size(max = 140) String description) {
}
