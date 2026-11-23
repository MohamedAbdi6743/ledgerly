package com.ledgerly.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A member or a staff member who can log in. Maps to app_user. */
@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	/** Only members have one, e.g. "M000042". */
	private String memberNumber;

	@Column(nullable = false)
	private boolean mustChangePassword;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected AppUser() {
	}

	public AppUser(String email, String passwordHash, String fullName, Role role, String memberNumber,
			boolean mustChangePassword, Instant createdAt) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.fullName = fullName;
		this.role = role;
		this.memberNumber = memberNumber;
		this.mustChangePassword = mustChangePassword;
		this.createdAt = createdAt;
	}

	/** Sets a new password the user chose themselves, which ends the temporary-password state. */
	public void changePassword(String newPasswordHash) {
		this.passwordHash = newPasswordHash;
		this.mustChangePassword = false;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getFullName() {
		return fullName;
	}

	public Role getRole() {
		return role;
	}

	public String getMemberNumber() {
		return memberNumber;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
