package com.ledgerly.controller;

import static com.ledgerly.controller.ApiTestSupport.activeMember;
import static com.ledgerly.controller.ApiTestSupport.activeTeller;
import static com.ledgerly.controller.ApiTestSupport.cash;
import static com.ledgerly.controller.ApiTestSupport.field;
import static com.ledgerly.controller.ApiTestSupport.loginAsManager;
import static com.ledgerly.controller.ApiTestSupport.openAccount;
import static com.ledgerly.controller.ApiTestSupport.transfer;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.controller.ApiTestSupport.Member;
import com.ledgerly.service.PaymentService;

/** Undoing mistakes with a new, opposite transaction, and every case where that isn't allowed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReversalApiTest {

	@Autowired
	private MockMvc mockMvc;

	private MockHttpSession teller;
	private MockHttpSession manager;
	private String samChequing;
	private String priyaChequing;

	@BeforeEach
	void setUp() throws Exception {
		teller = activeTeller(mockMvc, "dana@ledgerly.test");
		manager = loginAsManager(mockMvc);
		Member sam = activeMember(mockMvc, teller, "sam@example.com");
		Member priya = activeMember(mockMvc, teller, "priya@example.com");
		samChequing = openAccount(mockMvc, teller, sam.id(), "CHEQUING");
		priyaChequing = openAccount(mockMvc, teller, priya.id(), "CHEQUING");
	}

	@Test
	void reversingADepositPutsTheMoneyBackInTheDrawerAndLinksBothWays() throws Exception {
		String deposit = deposit(priyaChequing, "500.00");

		String reversal = field(reverse(deposit, "Typed 500 instead of 50", "reverse-0001")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("REVERSAL"))
				.andExpect(jsonPath("$.reverses").value(deposit))
				.andExpect(jsonPath("$.description").value("Typed 500 instead of 50"))
				.andExpect(jsonPath("$.fromAccount").value(priyaChequing))
				.andExpect(jsonPath("$.toAccount").value(nullValue()))
				.andExpect(jsonPath("$.entries[0].accountNumber").value(priyaChequing))
				.andExpect(jsonPath("$.entries[0].amount").value("-500.00"))
				.andExpect(jsonPath("$.entries[1].accountNumber").value(PaymentService.CASH_ON_HAND))
				.andExpect(jsonPath("$.entries[1].amount").value("500.00")), "$.reference");

		assertBalance(priyaChequing, "0.00");
		// The original is untouched, and points to what undid it.
		mockMvc.perform(get("/api/transactions/" + deposit).session(teller))
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.reversedBy").value(reversal));
		mockMvc.perform(get("/api/audit").session(manager)
						.param("entityType", "TRANSACTION").param("entityId", deposit))
				.andExpect(jsonPath("$[0].action").value("TRANSACTION_REVERSED"))
				.andExpect(jsonPath("$[0].details").value("Reversed by " + reversal + ": Typed 500 instead of 50"));
	}

	@Test
	void reversingATransferPutsBothAccountsBack() throws Exception {
		deposit(samChequing, "1000.00");
		String sent = field(transfer(mockMvc, teller, samChequing, priyaChequing, "300.00")
				.andExpect(status().isCreated()), "$.reference");

		reverse(sent, "Wrong recipient", "reverse-0001").andExpect(status().isCreated());

		assertBalance(samChequing, "1000.00");
		assertBalance(priyaChequing, "0.00");
	}

	@Test
	void aTransactionCanOnlyBeReversedOnceButARetryIsFine() throws Exception {
		String deposit = deposit(priyaChequing, "50.00");
		reverse(deposit, "Duplicate", "reverse-0001").andExpect(status().isCreated());

		reverse(deposit, "Duplicate", "reverse-0001")
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"));
		reverse(deposit, "Duplicate", "reverse-0002")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern(".*already reversed by TX-.*")));
	}

	@Test
	void cannotReverseMoneyThatWasAlreadySpent() throws Exception {
		String deposit = deposit(priyaChequing, "100.00");
		cash(mockMvc, teller, "/api/withdrawals", "spend-it", priyaChequing, "80.00").andExpect(status().isCreated());

		reverse(deposit, "Bad cheque", "reverse-0001")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern("Insufficient funds.*")));
		assertBalance(priyaChequing, "20.00");
	}

	@Test
	void pendingRequestsAndReversalsThemselvesCannotBeReversed() throws Exception {
		deposit(samChequing, "9000.00");
		String pending = field(cash(mockMvc, teller, "/api/withdrawals", "big-withdrawal", samChequing, "6000.00")
				.andExpect(status().isAccepted()), "$.reference");
		reverse(pending, "Changed mind", "reverse-0001")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern(".*Reject it instead.*")));

		String deposit = deposit(priyaChequing, "10.00");
		String reversal = field(reverse(deposit, "Oops", "reverse-0002"), "$.reference");
		reverse(reversal, "Undo the undo", "reverse-0003").andExpect(status().isConflict());
	}

	@Test
	void aFrozenAccountCanStillBeCorrected() throws Exception {
		String deposit = deposit(priyaChequing, "400.00");
		mockMvc.perform(put("/api/accounts/" + priyaChequing + "/status").with(csrf()).session(manager)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"status\": \"FROZEN\", \"reason\": \"Suspicious deposit\" }"))
				.andExpect(status().isOk());

		reverse(deposit, "Counterfeit notes", "reverse-0001").andExpect(status().isCreated());
		assertBalance(priyaChequing, "0.00");
	}

	@Test
	void onlyManagersReverseAndTheyMustSayWhy() throws Exception {
		String deposit = deposit(priyaChequing, "10.00");

		mockMvc.perform(post("/api/transactions/" + deposit + "/reversal").with(csrf()).session(teller)
						.header("Idempotency-Key", "reverse-0001")
						.contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"Mine\" }"))
				.andExpect(status().isForbidden());
		reverse(deposit, "", "reverse-0002").andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/transactions/" + deposit + "/reversal").with(csrf()).session(manager)
						.contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"No key\" }"))
				.andExpect(status().isBadRequest());
		reverse("TX-NOPE2345", "Missing", "reverse-0003").andExpect(status().isNotFound());
	}

	private String deposit(String accountNumber, String amount) throws Exception {
		return field(cash(mockMvc, teller, "/api/deposits", "deposit-" + accountNumber + "-" + amount.replace(".", ""), accountNumber,
				amount).andExpect(status().isCreated()), "$.reference");
	}

	private ResultActions reverse(String reference, String reason, String key) throws Exception {
		return mockMvc.perform(post("/api/transactions/" + reference + "/reversal").with(csrf()).session(manager)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"reason\": \"%s\" }".formatted(reason)));
	}

	private void assertBalance(String accountNumber, String expected) throws Exception {
		mockMvc.perform(get("/api/accounts/" + accountNumber).session(teller))
				.andExpect(jsonPath("$.account.balance").value(expected));
	}

}
