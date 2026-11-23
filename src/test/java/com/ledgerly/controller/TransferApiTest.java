package com.ledgerly.controller;

import static com.ledgerly.controller.ApiTestSupport.activeMember;
import static com.ledgerly.controller.ApiTestSupport.activeTeller;
import static com.ledgerly.controller.ApiTestSupport.cash;
import static com.ledgerly.controller.ApiTestSupport.loginAsManager;
import static com.ledgerly.controller.ApiTestSupport.openAccount;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.controller.ApiTestSupport.Member;
import com.ledgerly.service.PaymentService;

/** Transfers: who can send from where, the daily limit, and the usual money rules. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TransferApiTest {

	@Autowired
	private MockMvc mockMvc;

	private MockHttpSession teller;
	private Member sam;
	private Member priya;
	private String samChequing;
	private String samSavings;
	private String priyaChequing;

	@BeforeEach
	void setUp() throws Exception {
		teller = activeTeller(mockMvc, "dana@ledgerly.test");
		sam = activeMember(mockMvc, teller, "sam@example.com");
		priya = activeMember(mockMvc, teller, "priya@example.com");
		samChequing = openAccount(mockMvc, teller, sam.id(), "CHEQUING");
		samSavings = openAccount(mockMvc, teller, sam.id(), "SAVINGS");
		priyaChequing = openAccount(mockMvc, teller, priya.id(), "CHEQUING");
		cash(mockMvc, teller, "/api/deposits", "setup-deposit", samChequing, "5000.00").andExpect(status().isCreated());
	}

	@Test
	void memberMovesMoneyBetweenTheirOwnAccounts() throws Exception {
		transfer(sam.session(), samChequing, samSavings, "300.00")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reference").value(matchesPattern("TX-[A-Z2-9]{8}")))
				.andExpect(jsonPath("$.type").value("TRANSFER"))
				.andExpect(jsonPath("$.fromAccount").value(samChequing))
				.andExpect(jsonPath("$.toAccount").value(samSavings))
				.andExpect(jsonPath("$.entries[0].accountNumber").value(samChequing))
				.andExpect(jsonPath("$.entries[0].amount").value("-300.00"))
				.andExpect(jsonPath("$.entries[1].accountNumber").value(samSavings))
				.andExpect(jsonPath("$.entries[1].amount").value("300.00"));

		assertBalance(samChequing, "4700.00");
		assertBalance(samSavings, "300.00");
	}

	@Test
	void memberSendsMoneyToAnotherMember() throws Exception {
		transfer(sam.session(), samChequing, priyaChequing, "125.50").andExpect(status().isCreated());

		assertBalance(samChequing, "4874.50");
		assertBalance(priyaChequing, "125.50");
	}

	// ---------- the daily limit ----------

	@Test
	void sendingToOtherPeopleStopsAtThreeThousandDollarsADay() throws Exception {
		transfer(sam.session(), samChequing, priyaChequing, "2000.00").andExpect(status().isCreated());
		transfer(sam.session(), samChequing, priyaChequing, "1000.00").andExpect(status().isCreated());

		transfer(sam.session(), samChequing, priyaChequing, "0.01")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern(".*daily transfer limit.*\\$0\\.00 more today.*")));

		// Moving money between your own accounts never counts toward the limit.
		transfer(sam.session(), samChequing, samSavings, "1500.00").andExpect(status().isCreated());
	}

	@Test
	void staffTransfersHaveNoDailyLimit() throws Exception {
		transfer(teller, samChequing, priyaChequing, "4000.00").andExpect(status().isCreated());

		assertBalance(priyaChequing, "4000.00");
	}

	// ---------- refused transfers ----------

	@Test
	void memberCannotSendFromSomeoneElsesAccount() throws Exception {
		cash(mockMvc, teller, "/api/deposits", "priya-deposit", priyaChequing, "100.00").andExpect(status().isCreated());

		transfer(sam.session(), priyaChequing, samChequing, "50.00").andExpect(status().isNotFound());

		assertBalance(priyaChequing, "100.00");
	}

	@Test
	void transfersAreOnlyBetweenMemberAccounts() throws Exception {
		transfer(sam.session(), samChequing, PaymentService.CASH_ON_HAND, "10.00").andExpect(status().isBadRequest());
		transfer(teller, PaymentService.CASH_ON_HAND, samChequing, "10.00").andExpect(status().isBadRequest());
		transfer(sam.session(), samChequing, "100299999999", "10.00").andExpect(status().isNotFound());
		transfer(sam.session(), samChequing, samChequing, "10.00")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Choose two different accounts."));
	}

	@Test
	void cannotSendMoreThanTheBalance() throws Exception {
		transfer(sam.session(), samSavings, samChequing, "0.01")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern("Insufficient funds.*")));
	}

	@Test
	void cannotSendToAFrozenAccount() throws Exception {
		mockMvc.perform(put("/api/accounts/" + priyaChequing + "/status").with(csrf()).session(loginAsManager(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"status\": \"FROZEN\", \"reason\": \"Fraud check\" }"))
				.andExpect(status().isOk());

		transfer(sam.session(), samChequing, priyaChequing, "10.00")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern(".*frozen.*")));
		assertBalance(samChequing, "5000.00");
	}

	@Test
	void amountsFollowTheSameRulesAsCash() throws Exception {
		transfer(sam.session(), samChequing, samSavings, "10.005")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.amount").value("must have at most 2 decimal places"));
	}

	// ---------- retries ----------

	@Test
	void retryReturnsTheOriginalTransferAndMovesNoMoney() throws Exception {
		transfer(sam.session(), samChequing, priyaChequing, "40.00", "transfer-0001").andExpect(status().isCreated());

		transfer(sam.session(), samChequing, priyaChequing, "40.00", "transfer-0001")
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"));
		transfer(sam.session(), samChequing, priyaChequing, "400.00", "transfer-0001")
				.andExpect(status().is(422));

		assertBalance(priyaChequing, "40.00");
	}

	private ResultActions transfer(MockHttpSession session, String from, String to, String amount) throws Exception {
		return transfer(session, from, to, amount, UUID.randomUUID().toString());
	}

	private ResultActions transfer(MockHttpSession session, String from, String to, String amount, String key)
			throws Exception {
		return mockMvc.perform(post("/api/transfers").with(csrf()).session(session)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "fromAccount": "%s", "toAccount": "%s", "amount": "%s" }
						""".formatted(from, to, amount)));
	}

	private void assertBalance(String accountNumber, String expected) throws Exception {
		mockMvc.perform(get("/api/accounts/" + accountNumber).session(teller))
				.andExpect(jsonPath("$.account.balance").value(expected));
	}

}
