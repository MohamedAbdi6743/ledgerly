package com.ledgerly.controller;

import static com.ledgerly.controller.ApiTestSupport.activeMember;
import static com.ledgerly.controller.ApiTestSupport.activeTeller;
import static com.ledgerly.controller.ApiTestSupport.cash;
import static com.ledgerly.controller.ApiTestSupport.loginAsManager;
import static com.ledgerly.controller.ApiTestSupport.openAccount;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.controller.ApiTestSupport.Member;
import com.ledgerly.service.PaymentService;

/** Cash deposits and withdrawals: the entries they write, retries, and every way they can be refused. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CashApiTest {

	@Autowired
	private MockMvc mockMvc;

	private MockHttpSession teller;
	private Member sam;
	private String chequing;

	@BeforeEach
	void setUp() throws Exception {
		teller = activeTeller(mockMvc, "dana@ledgerly.test");
		sam = activeMember(mockMvc, teller, "sam@example.com");
		chequing = openAccount(mockMvc, teller, sam.id(), "CHEQUING");
	}

	@Test
	void depositWritesTwoEntriesThatAddUpToZero() throws Exception {
		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "200.00")
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andExpect(jsonPath("$.reference").value(matchesPattern("TX-[A-Z2-9]{8}")))
				.andExpect(jsonPath("$.type").value("DEPOSIT"))
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.amount").value("200.00"))
				.andExpect(jsonPath("$.toAccount").value(chequing))
				.andExpect(jsonPath("$.entries", hasSize(2)))
				.andExpect(jsonPath("$.entries[0].accountNumber").value(chequing))
				.andExpect(jsonPath("$.entries[0].amount").value("200.00"))
				.andExpect(jsonPath("$.entries[1].accountNumber").value(PaymentService.CASH_ON_HAND))
				.andExpect(jsonPath("$.entries[1].amount").value("-200.00"));

		mockMvc.perform(get("/api/accounts/" + chequing).session(sam.session()))
				.andExpect(jsonPath("$.account.balance").value("200.00"))
				.andExpect(jsonPath("$.recentActivity[0].type").value("DEPOSIT"))
				.andExpect(jsonPath("$.recentActivity[0].balanceAfter").value("200.00"));
	}

	@Test
	void withdrawalTakesMoneyOutAndPutsItBackInTheDrawer() throws Exception {
		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "100.00").andExpect(status().isCreated());

		cash(mockMvc, teller, "/api/withdrawals", "withdraw-0001", chequing, "30.00")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.fromAccount").value(chequing))
				.andExpect(jsonPath("$.entries[0].amount").value("-30.00"))
				.andExpect(jsonPath("$.entries[1].amount").value("30.00"));

		assertBalance("70.00");
	}

	// ---------- retries ----------

	@Test
	void retryWithTheSameKeyReturnsTheOriginalAndMovesNoMoney() throws Exception {
		String first = cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "200.00")
				.andReturn().getResponse().getContentAsString();
		String reference = JsonPath.read(first, "$.reference");

		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "200.00")
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.reference").value(reference));

		assertBalance("200.00");
	}

	@Test
	void reusingAKeyForADifferentRequestIsRefused() throws Exception {
		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "200.00").andExpect(status().isCreated());

		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "2000.00")
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.message").value(matchesPattern(".*different request.*")));

		assertBalance("200.00");
	}

	@Test
	void theIdempotencyKeyIsRequiredAndChecked() throws Exception {
		mockMvc.perform(post("/api/deposits").with(csrf()).session(teller)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"accountNumber\": \"%s\", \"amount\": \"10.00\" }".formatted(chequing)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(matchesPattern(".*Idempotency-Key.*required.*")));

		cash(mockMvc, teller, "/api/deposits", "short", chequing, "10.00").andExpect(status().isBadRequest());
		cash(mockMvc, teller, "/api/deposits", "has spaces in it", chequing, "10.00").andExpect(status().isBadRequest());
	}

	// ---------- refused amounts ----------

	@Test
	void amountsMustBePositiveWholeCentsAndNotHuge() throws Exception {
		// PostgreSQL would quietly round 10.005 to 10.01, so the API refuses it first.
		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "10.005")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.amount").value("must have at most 2 decimal places"));
		cash(mockMvc, teller, "/api/deposits", "deposit-0002", chequing, "0.00").andExpect(status().isBadRequest());
		cash(mockMvc, teller, "/api/deposits", "deposit-0003", chequing, "-5.00").andExpect(status().isBadRequest());
		cash(mockMvc, teller, "/api/deposits", "deposit-0004", chequing, "1000000.01").andExpect(status().isBadRequest());
		cash(mockMvc, teller, "/api/deposits", "deposit-0005", chequing, "lots").andExpect(status().isBadRequest());

		assertBalance("0.00");
	}

	@Test
	void cannotWithdrawMoreThanTheBalance() throws Exception {
		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "50.00").andExpect(status().isCreated());

		cash(mockMvc, teller, "/api/withdrawals", "withdraw-0001", chequing, "50.01")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern("Insufficient funds.*")));

		assertBalance("50.00");
	}

	@Test
	void frozenAccountsCannotTakeDepositsOrWithdrawals() throws Exception {
		mockMvc.perform(put("/api/accounts/" + chequing + "/status").with(csrf()).session(loginAsManager(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"status\": \"FROZEN\", \"reason\": \"Fraud check\" }"))
				.andExpect(status().isOk());

		cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "10.00")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern(".*frozen.*")));
	}

	@Test
	void cashCannotGoIntoTheCashDrawerItselfOrAMissingAccount() throws Exception {
		cash(mockMvc, teller, "/api/deposits", "deposit-0001", PaymentService.CASH_ON_HAND, "10.00")
				.andExpect(status().isBadRequest());
		cash(mockMvc, teller, "/api/deposits", "deposit-0002", "100299999999", "10.00")
				.andExpect(status().isNotFound());
	}

	@Test
	void membersCannotHandleCash() throws Exception {
		cash(mockMvc, sam.session(), "/api/deposits", "deposit-0001", chequing, "10.00")
				.andExpect(status().isForbidden());
	}

	// ---------- looking up a transaction ----------

	@Test
	void transactionIsVisibleToStaffAndTheAccountOwnerOnly() throws Exception {
		String json = cash(mockMvc, teller, "/api/deposits", "deposit-0001", chequing, "25.00")
				.andReturn().getResponse().getContentAsString();
		String reference = JsonPath.read(json, "$.reference");
		Member priya = activeMember(mockMvc, teller, "priya@example.com");

		mockMvc.perform(get("/api/transactions/" + reference).session(sam.session()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.amount").value("25.00"));
		mockMvc.perform(get("/api/transactions/" + reference).session(teller)).andExpect(status().isOk());
		mockMvc.perform(get("/api/transactions/" + reference).session(priya.session())).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/transactions/TX-NOPE2345").session(teller)).andExpect(status().isNotFound());
	}

	private void assertBalance(String expected) throws Exception {
		mockMvc.perform(get("/api/accounts/" + chequing).session(teller))
				.andExpect(jsonPath("$.account.balance").value(expected));
	}

}
