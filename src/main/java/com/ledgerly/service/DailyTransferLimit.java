package com.ledgerly.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ledgerly.dto.Money;
import com.ledgerly.exception.BusinessRuleException;

/**
 * The most a member can send per day to accounts they don't own.
 * "A day" is a calendar day in the credit union's time zone, not in UTC: at 11 p.m. in Toronto
 * it's already tomorrow in UTC, and the limit shouldn't reset at 7 p.m.
 */
@Component
public class DailyTransferLimit {

	/** Start (inclusive) and end (exclusive) of today, as exact moments. */
	public record Window(Instant from, Instant to) {
	}

	private final BigDecimal limit;
	private final ZoneId zone;
	private final Clock clock;

	public DailyTransferLimit(@Value("${ledgerly.transfers.daily-limit}") BigDecimal limit,
			@Value("${ledgerly.time-zone}") ZoneId zone, Clock clock) {
		this.limit = limit;
		this.zone = zone;
		this.clock = clock;
	}

	public Window today() {
		LocalDate today = LocalDate.now(clock.withZone(zone));
		return new Window(today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant());
	}

	public void check(BigDecimal alreadySentToday, BigDecimal amount) {
		BigDecimal left = limit.subtract(alreadySentToday).max(BigDecimal.ZERO);
		if (amount.compareTo(left) > 0) {
			throw new BusinessRuleException("This would go over your daily transfer limit of $" + Money.toText(limit)
					+ ". You can send up to $" + Money.toText(left) + " more today.");
		}
	}

}
