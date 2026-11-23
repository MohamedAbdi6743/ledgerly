package com.ledgerly.controller;

import static com.ledgerly.controller.ApiTestSupport.activate;
import static com.ledgerly.controller.ApiTestSupport.activeTeller;
import static com.ledgerly.controller.ApiTestSupport.create;
import static com.ledgerly.controller.ApiTestSupport.login;
import static com.ledgerly.controller.ApiTestSupport.loginAsManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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

import com.jayway.jsonpath.JsonPath;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.entity.AuditEvent;
import com.ledgerly.repository.AuditEventRepository;

/** Creating and finding members and staff, and who is allowed to do it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class MemberApiTest {

	private static final String SAM = "{ \"fullName\": \"Sam Ortiz\", \"email\": \"Sam@Example.com\" }";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuditEventRepository auditEventRepository;

	// ---------- creating members ----------

	@Test
	void tellerCreatesMemberWithMemberNumberAndTemporaryPassword() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");

		String json = createMember(teller, SAM)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("sam@example.com"))
				.andExpect(jsonPath("$.role").value("MEMBER"))
				.andExpect(jsonPath("$.memberNumber").value(matchesPattern("M\\d{6}")))
				.andExpect(jsonPath("$.temporaryPassword").value(matchesPattern("[A-Za-z2-9]{14}")))
				.andReturn().getResponse().getContentAsString();

		// The temporary password works, and the member is told to change it.
		MockHttpSession sam = login(mockMvc, "sam@example.com", JsonPath.read(json, "$.temporaryPassword"));
		mockMvc.perform(get("/api/auth/me").session(sam))
				.andExpect(jsonPath("$.mustChangePassword").value(true));
	}

	@Test
	void duplicateEmailIsRefusedRegardlessOfCapitals() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		createMember(teller, SAM).andExpect(status().isCreated());

		createMember(teller, "{ \"fullName\": \"Sam O\", \"email\": \"SAM@EXAMPLE.COM\" }")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("An account with this email already exists."));
	}

	@Test
	void invalidFieldsReturn400WithEachField() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");

		createMember(teller, "{ \"fullName\": \"\", \"email\": \"not-an-email\" }")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.fullName").exists())
				.andExpect(jsonPath("$.fieldErrors.email").exists());
	}

	@Test
	void creatingAMemberIsAudited() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		String json = createMember(teller, SAM).andReturn().getResponse().getContentAsString();
		String memberId = String.valueOf((Number) JsonPath.read(json, "$.id"));

		List<AuditEvent> events = auditEventRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("USER", memberId);

		assertThat(events).hasSize(1);
		assertThat(events.get(0).getAction()).isEqualTo("MEMBER_CREATED");
		assertThat(events.get(0).getActor().getEmail()).isEqualTo("dana@ledgerly.test");
	}

	// ---------- who may do what ----------

	@Test
	void membersCannotCreateOrSearchMembers() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		MockHttpSession sam = activate(mockMvc, "sam@example.com", createMember(teller, SAM)
				.andReturn().getResponse().getContentAsString());

		createMember(sam, "{ \"fullName\": \"Lee Park\", \"email\": \"lee@example.com\" }")
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/members").session(sam))
				.andExpect(status().isForbidden());
	}

	@Test
	void tellersCannotCreateStaff() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");

		mockMvc.perform(post("/api/staff").with(csrf()).session(teller)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"fullName\": \"Omar H\", \"email\": \"omar@ledgerly.test\", \"role\": \"MANAGER\" }"))
				.andExpect(status().isForbidden());
	}

	@Test
	void managerCreatesTellerWithoutMemberNumber() throws Exception {
		String json = create(mockMvc, loginAsManager(mockMvc), "/api/staff",
				"{ \"fullName\": \"Dana Lee\", \"email\": \"dana@ledgerly.test\", \"role\": \"TELLER\" }");

		assertThat((String) JsonPath.read(json, "$.role")).isEqualTo("TELLER");
		assertThat((Object) JsonPath.read(json, "$.memberNumber")).isNull();
	}

	@Test
	void staffEndpointRefusesTheMemberRole() throws Exception {
		mockMvc.perform(post("/api/staff").with(csrf()).session(loginAsManager(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"fullName\": \"Sam Ortiz\", \"email\": \"sam@example.com\", \"role\": \"MEMBER\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Staff role must be TELLER or MANAGER."));
	}

	// ---------- finding members ----------

	@Test
	void searchFindsMembersByNameEmailOrMemberNumber() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		String json = createMember(teller, SAM).andReturn().getResponse().getContentAsString();
		String memberNumber = JsonPath.read(json, "$.memberNumber");
		createMember(teller, "{ \"fullName\": \"Priya Shah\", \"email\": \"priya@example.com\" }");

		search(teller, "ortiz").andExpect(jsonPath("$[0].fullName").value("Sam Ortiz"));
		search(teller, "PRIYA@").andExpect(jsonPath("$[0].fullName").value("Priya Shah"));
		search(teller, memberNumber).andExpect(jsonPath("$[0].email").value("sam@example.com"));
	}

	@Test
	void searchNeverReturnsStaff() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");

		search(teller, "dana").andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void getMemberByIdAndUnknownOrStaffIdsReturn404() throws Exception {
		MockHttpSession teller = activeTeller(mockMvc, "dana@ledgerly.test");
		String json = createMember(teller, SAM).andReturn().getResponse().getContentAsString();
		Number memberId = JsonPath.read(json, "$.id");
		Number tellerId = JsonPath.read(mockMvc.perform(get("/api/auth/me").session(teller))
				.andReturn().getResponse().getContentAsString(), "$.id");

		mockMvc.perform(get("/api/members/" + memberId).session(teller))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Sam Ortiz"));
		mockMvc.perform(get("/api/members/999999").session(teller))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/members/" + tellerId).session(teller))
				.andExpect(status().isNotFound());
	}

	// ---------- helpers ----------

	private ResultActions createMember(MockHttpSession session, String json) throws Exception {
		return mockMvc.perform(post("/api/members").with(csrf()).session(session)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions search(MockHttpSession session, String text) throws Exception {
		return mockMvc.perform(get("/api/members").param("query", text).session(session));
	}

}
