package com.ledgerly.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One line in the audit trail: who did what to which record, and when.
 * Append-only: there are no setters, and nothing ever updates or deletes these rows.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id")
	private AppUser actor;

	@Column(nullable = false)
	private String action;

	@Column(nullable = false)
	private String entityType;

	@Column(nullable = false)
	private String entityId;

	private String details;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected AuditEvent() {
	}

	public AuditEvent(AppUser actor, String action, String entityType, String entityId, String details, Instant createdAt) {
		this.actor = actor;
		this.action = action;
		this.entityType = entityType;
		this.entityId = entityId;
		this.details = details;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public AppUser getActor() {
		return actor;
	}

	public String getAction() {
		return action;
	}

	public String getEntityType() {
		return entityType;
	}

	public String getEntityId() {
		return entityId;
	}

	public String getDetails() {
		return details;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
