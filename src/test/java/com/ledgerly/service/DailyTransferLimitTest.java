package com.ledgerly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.service.DailyTransferLimit.Window;

/** "Today" is a Toronto calendar day, and the limit counts what's left of $3,000. */
class DailyTransferLimitTest {

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	@Test
	void lateEveningInTorontoIsStillTodayEvenThoughItIsTomorrowInUtc() {
		// 23:30 in Toronto on November 22 is 04:30 UTC on November 23.
		Window window = limitAt("2026-11-23T04:30:00Z").today();

		assertThat(window.from()).isEqualTo(Instant.parse("2026-11-22T05:00:00Z"));   // midnight Toronto (UTC-5)
		assertThat(window.to()).isEqualTo(Instant.parse("2026-11-23T05:00:00Z"));
	}

	@Test
	void theDayClocksGoBackIsTwentyFiveHoursLong() {
		// Daylight saving time ended in Toronto on November 1, 2026.
		Window window = limitAt("2026-11-01T15:00:00Z").today();

		assertThat(window.from()).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));   // midnight at UTC-4
		assertThat(window.to()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));     // next midnight at UTC-5
	}

	@Test
	void allowsUpToWhatIsLeftAndNotOneCentMore() {
		DailyTransferLimit limit = limitAt("2026-11-22T15:00:00Z");

		assertThatCode(() -> limit.check(money("2000.00"), money("1000.00"))).doesNotThrowAnyException();
		assertThatThrownBy(() -> limit.check(money("2000.00"), money("1000.01")))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("$3000.00")
				.hasMessageContaining("up to $1000.00 more");
		assertThatThrownBy(() -> limit.check(money("3000.00"), money("0.01")))
				.hasMessageContaining("up to $0.00 more");
	}

	private static DailyTransferLimit limitAt(String instant) {
		return new DailyTransferLimit(money("3000.00"), TORONTO, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
	}

	private static BigDecimal money(String amount) {
		return new BigDecimal(amount);
	}

}
