package com.ledgerly.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * Small helpers shared by the API tests: log in the way the browser does, and create
 * accounts through the real endpoints (so tests use temporary passwords exactly like people do).
 */
final class ApiTestSupport {

	static final String MANAGER_EMAIL = "manager@ledgerly.test";        // from src/test/resources/application.properties
	static final String MANAGER_PASSWORD = "test-manager-password";
	static final String NEW_PASSWORD = "a-new-long-password-1";

	private ApiTestSupport() {
	}

	static MockHttpSession login(MockMvc mockMvc, String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
						.param("email", email).param("password", password))
				.andExpect(status().isOk())
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	static MockHttpSession loginAsManager(MockMvc mockMvc) throws Exception {
		return login(mockMvc, MANAGER_EMAIL, MANAGER_PASSWORD);
	}

	/** Creates an account through the API and returns its JSON (which includes the temporary password). */
	static String create(MockMvc mockMvc, MockHttpSession creator, String path, String json) throws Exception {
		return mockMvc.perform(post(path).with(csrf()).session(creator)
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	/** Logs in with the temporary password, changes it, and returns a session that can use the API. */
	static MockHttpSession activate(MockMvc mockMvc, String email, String createdJson) throws Exception {
		String temporaryPassword = JsonPath.read(createdJson, "$.temporaryPassword");
		MockHttpSession session = login(mockMvc, email, temporaryPassword);
		mockMvc.perform(post("/api/auth/password").with(csrf()).session(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "currentPassword": "%s", "newPassword": "%s" }
								""".formatted(temporaryPassword, NEW_PASSWORD)))
				.andExpect(status().isNoContent());
		return session;
	}

	static MockHttpSession activeTeller(MockMvc mockMvc, String email) throws Exception {
		String json = create(mockMvc, loginAsManager(mockMvc), "/api/staff", """
				{ "fullName": "Dana Lee", "email": "%s", "role": "TELLER" }
				""".formatted(email));
		return activate(mockMvc, email, json);
	}

	/** A member who has logged in and picked their own password. */
	record Member(MockHttpSession session, long id) {
	}

	static Member activeMember(MockMvc mockMvc, MockHttpSession staff, String email) throws Exception {
		String json = create(mockMvc, staff, "/api/members", """
				{ "fullName": "Sam Ortiz", "email": "%s" }
				""".formatted(email));
		long id = ((Number) JsonPath.read(json, "$.id")).longValue();
		return new Member(activate(mockMvc, email, json), id);
	}

	/** Opens an account through the API and returns its account number. */
	static String openAccount(MockMvc mockMvc, MockHttpSession staff, long memberId, String type) throws Exception {
		String json = create(mockMvc, staff, "/api/members/" + memberId + "/accounts", """
				{ "type": "%s", "name": "My %s" }
				""".formatted(type, type.toLowerCase()));
		return JsonPath.read(json, "$.accountNumber");
	}

	/** POST /api/deposits or /api/withdrawals with an Idempotency-Key. */
	static ResultActions cash(MockMvc mockMvc, MockHttpSession staff, String path, String key, String accountNumber,
			String amount) throws Exception {
		return mockMvc.perform(post(path).with(csrf()).session(staff)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "accountNumber": "%s", "amount": "%s" }
						""".formatted(accountNumber, amount)));
	}

}
