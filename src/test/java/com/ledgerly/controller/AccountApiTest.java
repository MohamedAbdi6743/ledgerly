package com.ledgerly.controller;

import static com.ledgerly.controller.ApiTestSupport.activeMember;
import static com.ledgerly.controller.ApiTestSupport.activeTeller;
import static com.ledgerly.controller.ApiTestSupport.loginAsManager;
import static com.ledgerly.controller.ApiTestSupport.openAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.ledgerly.entity.AuditEvent;
import com.ledgerly.repository.AuditEventRepository;
import com.ledgerly.service.PaymentService;

/** Opening, viewing and freezing accounts, and who may do each. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuditEventRepository auditEventRepository;

	@Test
	void tellerOpensAnEmptyCadAccountForAMember() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		Member sam = activeMember(mockMvc, teller, "sam@example.com");

		mockMvc.perform(post("/api/members/" + sam.id() + "/accounts").with(csrf()).session(teller)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"type\": \"CHEQUING\", \"name\": \"Everyday\" }"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accountNumber").value(matchesPattern("1002\\d{8}")))
				.andExpect(jsonPath("$.type").value("CHEQUING"))
				.andExpect(jsonPath("$.currency").value("CAD"))
				.andExpect(jsonPath("$.balance").value("0.00"))
				.andExpect(jsonPath("$.status").value("OPEN"));

		assertThat(auditEventRepository.findAll()).extracting(AuditEvent::getAction).contains("ACCOUNT_OPENED");
	}

	@Test
	void cannotOpenSystemAccountsOrAccountsForUnknownMembers() throws Exception {
		MockHttpSession manager = loginAsManager(mockMvc);
		Member sam = activeMember(mockMvc, manager, "sam@example.com");

		open(manager, sam.id(), "SYSTEM").andExpect(status().isBadRequest());
		open(manager, 999_999L, "SAVINGS").andExpect(status().isNotFound());
	}

	@Test
	void memberSeesOnlyTheirOwnAccounts() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		Member sam = activeMember(mockMvc, teller, "sam@example.com");
		Member priya = activeMember(mockMvc, teller, "priya@example.com");
		String samChequing = openAccount(mockMvc, teller, sam.id(), "CHEQUING");
		openAccount(mockMvc, teller, sam.id(), "SAVINGS");
		String priyaChequing = openAccount(mockMvc, teller, priya.id(), "CHEQUING");

		mockMvc.perform(get("/api/me/accounts").session(sam.session()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].accountNumber").value(samChequing));

		mockMvc.perform(get("/api/accounts/" + samChequing).session(sam.session()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.accountNumber").value(samChequing))
				.andExpect(jsonPath("$.recentActivity", hasSize(0)));

		// Someone else's account looks exactly like one that doesn't exist.
		mockMvc.perform(get("/api/accounts/" + priyaChequing).session(sam.session()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/accounts/" + PaymentService.CASH_ON_HAND).session(sam.session()))
				.andExpect(status().isNotFound());

		// Staff can see any account, including the cash drawer.
		mockMvc.perform(get("/api/accounts/" + PaymentService.CASH_ON_HAND).session(teller))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/members/" + priya.id() + "/accounts").session(teller))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void membersCannotOpenAccountsAndStaffHaveNoMyAccountsPage() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		Member sam = activeMember(mockMvc, teller, "sam@example.com");

		open(sam.session(), sam.id(), "SAVINGS").andExpect(status().isForbidden());
		mockMvc.perform(get("/api/me/accounts").session(teller)).andExpect(status().isForbidden());
	}

	@Test
	void onlyManagersFreezeAccountsAndTheReasonIsAudited() throws Exception {
		MockHttpSession manager = loginAsManager(mockMvc);
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		Member sam = activeMember(mockMvc, teller, "sam@example.com");
		String chequing = openAccount(mockMvc, teller, sam.id(), "CHEQUING");

		setStatus(teller, chequing, "FROZEN").andExpect(status().isForbidden());

		setStatus(manager, chequing, "FROZEN")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FROZEN"));
		setStatus(manager, chequing, "FROZEN").andExpect(status().isConflict());
		setStatus(manager, chequing, "OPEN").andExpect(jsonPath("$.status").value("OPEN"));
		setStatus(manager, PaymentService.CASH_ON_HAND, "FROZEN").andExpect(status().isBadRequest());

		assertThat(auditEventRepository.findAll())
				.filteredOn(event -> event.getAction().equals("ACCOUNT_FROZEN"))
				.singleElement()
				.satisfies(event -> assertThat(event.getDetails()).isEqualTo("Reported stolen card"));
	}

	private ResultActions open(MockHttpSession session, long memberId, String type) throws Exception {
		return mockMvc.perform(post("/api/members/" + memberId + "/accounts").with(csrf()).session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"type\": \"%s\", \"name\": \"Test\" }".formatted(type)));
	}

	private ResultActions setStatus(MockHttpSession session, String accountNumber, String newStatus) throws Exception {
		return mockMvc.perform(put("/api/accounts/" + accountNumber + "/status").with(csrf()).session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"status\": \"%s\", \"reason\": \"Reported stolen card\" }".formatted(newStatus)));
	}

}
