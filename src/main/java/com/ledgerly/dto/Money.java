package com.ledgerly.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amounts leave the API as strings like "300.00". As a JSON number, JavaScript would turn
 * them into floating point, and 0.1 + 0.2 would stop being 0.3.
 */
public final class Money {

	private Money() {
	}

	public static String toText(BigDecimal amount) {
		// UNNECESSARY: amounts are already exact cents; if one isn't, that's a bug and should fail loudly.
		return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
	}

}
