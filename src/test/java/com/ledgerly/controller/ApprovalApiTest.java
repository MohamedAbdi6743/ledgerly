package com.ledgerly.controller;

import static com.ledgerly.controller.ApiTestSupport.activeManager;
import static com.ledgerly.controller.ApiTestSupport.activeMember;
import static com.ledgerly.controller.ApiTestSupport.activeTeller;
import static com.ledgerly.controller.ApiTestSupport.cash;
import static com.ledgerly.controller.ApiTestSupport.field;
import static com.ledgerly.controller.ApiTestSupport.loginAsManager;
import static com.ledgerly.controller.ApiTestSupport.openAccount;
import static com.ledgerly.controller.ApiTestSupport.transfer;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Maker-checker: large requests wait, a different manager decides, and the audit log records it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ApprovalApiTest {

	@Autowired
	private MockMvc mockMvc;

	private MockHttpSession teller;
	private MockHttpSession manager;
	private Member sam;
	private String samChequing;
	private String samSavings;
	private String priyaChequing;

	@BeforeEach
	void setUp() throws Exception {
		teller = activeTeller(mockMvc, "dana@ledgerly.test");
		manager = loginAsManager(mockMvc);
		sam = activeMember(mockMvc, teller, "sam@example.com");
		Member priya = activeMember(mockMvc, teller, "priya@example.com");
		samChequing = openAccount(mockMvc, teller, sam.id(), "CHEQUING");
		samSavings = openAccount(mockMvc, teller, sam.id(), "SAVINGS");
		priyaChequing = openAccount(mockMvc, teller, priya.id(), "CHEQUING");
		cash(mockMvc, teller, "/api/deposits", "setup-deposit", samChequing, "10000.00").andExpect(status().isCreated());
	}

	@Test
	void withdrawalsOfFiveThousandOrMoreWaitAndMoveNoMoney() throws Exception {
		cash(mockMvc, teller, "/api/withdrawals", "withdraw-small", samChequing, "4999.99")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("POSTED"));

		String reference = field(cash(mockMvc, teller, "/api/withdrawals", "withdraw-large", samChequing, "5000.00")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
				.andExpect(jsonPath("$.postedAt").value(nullValue()))
				.andExpect(jsonPath("$.entries", hasSize(0))), "$.reference");

		assertBalance(samChequing, "5000.01");
		mockMvc.perform(get("/api/approvals").session(manager))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].transaction.reference").value(reference))
				.andExpect(jsonPath("$[0].requestedByEmail").value("dana@ledgerly.test"));
	}

	@Test
	void aRetryOfAPendingRequestGetsTheSameAnswer() throws Exception {
		cash(mockMvc, teller, "/api/withdrawals", "withdraw-large", samChequing, "6000.00").andExpect(status().isAccepted());

		cash(mockMvc, teller, "/api/withdrawals", "withdraw-large", samChequing, "6000.00")
				.andExpect(status().isAccepted())
				.andExpect(header().string("Idempotent-Replayed", "true"));
		mockMvc.perform(get("/api/approvals").session(manager)).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void largeTransfersToSomeoneElseWaitButMovingYourOwnMoneyDoesNot() throws Exception {
		transfer(mockMvc, teller, samChequing, priyaChequing, "5000.00")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
		transfer(mockMvc, teller, samChequing, samSavings, "5000.00")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("POSTED"));
	}

	@Test
	void aManagerApprovesAndTheMoneyMoves() throws Exception {
		String reference = pendingWithdrawal(teller, "7000.00");

		mockMvc.perform(post("/api/approvals/" + reference + "/approve").with(csrf()).session(manager))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.postedAt").value(not(nullValue())))
				.andExpect(jsonPath("$.entries[0].amount").value("-7000.00"));
		assertBalance(samChequing, "3000.00");

		// Deciding twice isn't possible.
		mockMvc.perform(post("/api/approvals/" + reference + "/approve").with(csrf()).session(manager))
				.andExpect(status().isConflict());
		mockMvc.perform(get("/api/approvals").session(manager)).andExpect(jsonPath("$", hasSize(0)));

		mockMvc.perform(get("/api/audit").session(manager)
						.param("entityType", "TRANSACTION").param("entityId", reference))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].action").value("TRANSACTION_APPROVED"))
				.andExpect(jsonPath("$[0].actorEmail").value("manager@ledgerly.test"));
	}

	@Test
	void nobodyCanApproveTheirOwnRequest() throws Exception {
		String reference = pendingWithdrawal(manager, "6000.00");

		mockMvc.perform(post("/api/approvals/" + reference + "/approve").with(csrf()).session(manager))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(matchesPattern(".*your own request.*")));

		MockHttpSession secondManager = activeManager(mockMvc, "omar@ledgerly.test");
		mockMvc.perform(post("/api/approvals/" + reference + "/approve").with(csrf()).session(secondManager))
				.andExpect(status().isOk());
	}

	@Test
	void aRejectionNeedsAReasonAndMovesNoMoney() throws Exception {
		String reference = pendingWithdrawal(teller, "6000.00");

		reject(reference, "").andExpect(status().isBadRequest());
		reject(reference, "Member not present with ID")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.decisionReason").value("Member not present with ID"));

		assertBalance(samChequing, "10000.00");
		mockMvc.perform(post("/api/approvals/" + reference + "/approve").with(csrf()).session(manager))
				.andExpect(status().isConflict());
		// The member can see what happened and why.
		mockMvc.perform(get("/api/transactions/" + reference).session(sam.session()))
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.decisionReason").value("Member not present with ID"));
	}

	@Test
	void approvalChecksTheBalanceAgainBecauseItMayHaveChanged() throws Exception {
		String reference = pendingWithdrawal(teller, "8000.00");
		cash(mockMvc, teller, "/api/withdrawals", "meanwhile", samChequing, "3000.00").andExpect(status().isCreated());

		mockMvc.perform(post("/api/approvals/" + reference + "/approve").with(csrf()).session(manager))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(matchesPattern("Insufficient funds.*")));

		assertBalance(samChequing, "7000.00");
		mockMvc.perform(get("/api/approvals").session(manager))
				.andExpect(jsonPath("$[*].transaction.reference", hasItem(reference)));
	}

	@Test
	void aRequestThatCouldNeverGoThroughIsRefusedRightAway() throws Exception {
		cash(mockMvc, teller, "/api/withdrawals", "too-much", samChequing, "20000.00")
				.andExpect(status().isConflict());
		mockMvc.perform(get("/api/approvals").session(manager)).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void onlyManagersSeeTheQueueDecideOrReadTheAuditLog() throws Exception {
		String reference = pendingWithdrawal(teller, "6000.00");

		mockMvc.perform(get("/api/approvals").session(teller)).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/approvals/" + reference + "/approve").with(csrf()).session(teller))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/audit").session(teller)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/audit").session(manager).param("entityType", "TRANSACTION"))
				.andExpect(status().isBadRequest());
	}

	private String pendingWithdrawal(MockHttpSession staff, String amount) throws Exception {
		return field(cash(mockMvc, staff, "/api/withdrawals", "pending-" + amount.replace(".", ""), samChequing, amount)
				.andExpect(status().isAccepted()), "$.reference");
	}

	private ResultActions reject(String reference, String reason) throws Exception {
		return mockMvc.perform(post("/api/approvals/" + reference + "/reject").with(csrf()).session(manager)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"reason\": \"%s\" }".formatted(reason)));
	}

	private void assertBalance(String accountNumber, String expected) throws Exception {
		mockMvc.perform(get("/api/accounts/" + accountNumber).session(teller))
				.andExpect(jsonPath("$.account.balance").value(expected));
	}

}
