package com.ledgerly.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.dto.NewUserResponse;
import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.Role;
import com.ledgerly.exception.BadRequestException;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.exception.NotFoundException;
import com.ledgerly.repository.UserRepository;

/**
 * Creating and finding people. Members never sign themselves up: staff create them after
 * checking their identity, and the person gets a temporary password to change at first login.
 */
@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);
	private static final int SEARCH_LIMIT = 20;

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final TemporaryPasswordGenerator passwordGenerator;
	private final AuditService auditService;
	private final Clock clock;

	public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
			TemporaryPasswordGenerator passwordGenerator, AuditService auditService, Clock clock) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.passwordGenerator = passwordGenerator;
		this.auditService = auditService;
		this.clock = clock;
	}

	@Transactional
	public NewUserResponse createMember(String fullName, String email, String staffEmail) {
		AppUser staff = getByEmail(staffEmail);
		String memberNumber = String.format("M%06d", userRepository.nextMemberNumber());
		return createWithTemporaryPassword(fullName, email, Role.MEMBER, memberNumber, staff, "MEMBER_CREATED");
	}

	@Transactional
	public NewUserResponse createStaff(String fullName, String email, Role role, String managerEmail) {
		if (!role.isStaff()) {
			throw new BadRequestException("Staff role must be TELLER or MANAGER.");
		}
		AppUser manager = getByEmail(managerEmail);
		return createWithTemporaryPassword(fullName, email, role, null, manager, "STAFF_CREATED");
	}

	/** Used once at startup for the first manager, whose password comes from settings. */
	@Transactional
	public AppUser createInitialManager(String fullName, String email, String password) {
		String normalizedEmail = normalizeEmail(email);
		AppUser manager = userRepository.save(new AppUser(normalizedEmail, passwordEncoder.encode(password),
				fullName.trim(), Role.MANAGER, null, false, Instant.now(clock)));
		auditService.record(null, "STAFF_CREATED", "USER", manager.getId().toString(), "First manager, from settings");
		return manager;
	}

	@Transactional
	public void changePassword(String email, String currentPassword, String newPassword) {
		AppUser user = getByEmail(email);
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new BadRequestException("Current password is incorrect.");
		}
		if (currentPassword.equals(newPassword)) {
			throw new BadRequestException("New password must be different from the current one.");
		}
		user.changePassword(passwordEncoder.encode(newPassword));
		auditService.record(user, "PASSWORD_CHANGED", "USER", user.getId().toString(), null);
	}

	@Transactional(readOnly = true)
	public List<AppUser> searchMembers(String text) {
		String query = (text == null) ? "" : text.trim();
		return userRepository.searchMembers(query, PageRequest.of(0, SEARCH_LIMIT));
	}

	@Transactional(readOnly = true)
	public AppUser getMember(Long id) {
		return userRepository.findByIdAndRole(id, Role.MEMBER)
				.orElseThrow(() -> new NotFoundException("Member not found."));
	}

	@Transactional(readOnly = true)
	public AppUser getByEmail(String email) {
		return userRepository.findByEmail(normalizeEmail(email))
				.orElseThrow(() -> new NotFoundException("User not found."));
	}

	@Transactional(readOnly = true)
	public Optional<AppUser> findByEmail(String email) {
		return userRepository.findByEmail(normalizeEmail(email));
	}

	@Transactional(readOnly = true)
	public boolean emailExists(String email) {
		return userRepository.existsByEmail(normalizeEmail(email));
	}

	public static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private NewUserResponse createWithTemporaryPassword(String fullName, String email, Role role,
			String memberNumber, AppUser createdBy, String auditAction) {
		String normalizedEmail = normalizeEmail(email);
		if (userRepository.existsByEmail(normalizedEmail)) {
			throw new BusinessRuleException("An account with this email already exists.");
		}

		String temporaryPassword = passwordGenerator.generate();
		AppUser user = userRepository.save(new AppUser(normalizedEmail, passwordEncoder.encode(temporaryPassword),
				fullName.trim(), role, memberNumber, true, Instant.now(clock)));

		auditService.record(createdBy, auditAction, "USER", user.getId().toString(), "Role " + role);
		// Log ids only: no emails, and never the temporary password.
		log.info("User {} created {} account {}", createdBy.getId(), role, user.getId());
		return new NewUserResponse(user.getId(), user.getFullName(), user.getEmail(), role, memberNumber, temporaryPassword);
	}

}
