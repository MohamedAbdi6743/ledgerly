package com.ledgerly.controller;

import static com.ledgerly.controller.ApiTestSupport.MANAGER_EMAIL;
import static com.ledgerly.controller.ApiTestSupport.MANAGER_PASSWORD;
import static com.ledgerly.controller.ApiTestSupport.NEW_PASSWORD;
import static com.ledgerly.controller.ApiTestSupport.activate;
import static com.ledgerly.controller.ApiTestSupport.create;
import static com.ledgerly.controller.ApiTestSupport.login;
import static com.ledgerly.controller.ApiTestSupport.loginAsManager;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.ledgerly.TestcontainersConfiguration;

/** Login, logout, the temporary-password gate and password changes, through the HTTP API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void firstManagerFromSettingsCanLogIn() throws Exception {
		mockMvc.perform(post("/api/auth/login").with(csrf())
						.param("email", MANAGER_EMAIL).param("password", MANAGER_PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("MANAGER"))
				.andExpect(jsonPath("$.mustChangePassword").value(false))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void wrongPasswordReturns401() throws Exception {
		mockMvc.perform(post("/api/auth/login").with(csrf())
						.param("email", MANAGER_EMAIL).param("password", "not-the-password"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Email or password is incorrect."));
	}

	@Test
	void apiRequiresLogin() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Please log in first."));
	}

	@Test
	void changesWithoutCsrfTokenAreRefused() throws Exception {
		MockHttpSession manager = loginAsManager(mockMvc);

		mockMvc.perform(post("/api/members").session(manager)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"fullName\": \"Sam Ortiz\", \"email\": \"sam@example.com\" }"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(containsString("CSRF")));
	}

	@Test
	void logoutEndsTheSession() throws Exception {
		MockHttpSession manager = loginAsManager(mockMvc);

		mockMvc.perform(post("/api/auth/logout").with(csrf()).session(manager))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/auth/me").session(manager))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void responsesCarryAContentSecurityPolicy() throws Exception {
		mockMvc.perform(get("/api/auth/csrf"))
				.andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
	}

	@Test
	void temporaryPasswordBlocksTheApiUntilItIsChanged() throws Exception {
		String json = create(mockMvc, loginAsManager(mockMvc), "/api/staff",
				"{ \"fullName\": \"Dana Lee\", \"email\": \"dana@ledgerly.test\", \"role\": \"TELLER\" }");
		String temporaryPassword = JsonPath.read(json, "$.temporaryPassword");
		MockHttpSession teller = login(mockMvc, "dana@ledgerly.test", temporaryPassword);

		mockMvc.perform(get("/api/auth/me").session(teller))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mustChangePassword").value(true));
		mockMvc.perform(get("/api/members").session(teller))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Please change your temporary password first."));

		changePassword(teller, temporaryPassword, NEW_PASSWORD).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/members").session(teller))
				.andExpect(status().isOk());
	}

	@Test
	void changingPasswordNeedsTheCorrectCurrentPassword() throws Exception {
		MockHttpSession manager = loginAsManager(mockMvc);

		changePassword(manager, "wrong-current-password", NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Current password is incorrect."));
	}

	@Test
	void newPasswordMustBeAtLeastTwelveCharacters() throws Exception {
		MockHttpSession manager = loginAsManager(mockMvc);

		changePassword(manager, MANAGER_PASSWORD, "short")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.newPassword").exists());
	}

	@Test
	void newPasswordMustDifferFromTheCurrentOne() throws Exception {
		MockHttpSession manager = loginAsManager(mockMvc);

		changePassword(manager, MANAGER_PASSWORD, MANAGER_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("New password must be different from the current one."));
	}

	@Test
	void afterChangingPasswordOnlyTheNewOneWorks() throws Exception {
		String json = create(mockMvc, loginAsManager(mockMvc), "/api/staff",
				"{ \"fullName\": \"Dana Lee\", \"email\": \"dana@ledgerly.test\", \"role\": \"TELLER\" }");
		String temporaryPassword = JsonPath.read(json, "$.temporaryPassword");
		activate(mockMvc, "dana@ledgerly.test", json);

		mockMvc.perform(post("/api/auth/login").with(csrf())
						.param("email", "dana@ledgerly.test").param("password", temporaryPassword))
				.andExpect(status().isUnauthorized());
		login(mockMvc, "dana@ledgerly.test", NEW_PASSWORD);
	}

	private org.springframework.test.web.servlet.ResultActions changePassword(MockHttpSession session,
			String current, String next) throws Exception {
		return mockMvc.perform(post("/api/auth/password").with(csrf()).session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"currentPassword\": \"%s\", \"newPassword\": \"%s\" }".formatted(current, next)));
	}

}
