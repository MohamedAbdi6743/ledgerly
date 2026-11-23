package com.ledgerly.dto;

import java.time.Instant;

import com.ledgerly.entity.AuditEvent;

/** One audit line. actor is null for things the system did itself (like creating the first manager). */
public record AuditEventResponse(String actor, String actorEmail, String action, String entityType, String entityId,
		String details, Instant createdAt) {

	public static AuditEventResponse from(AuditEvent event) {
		return new AuditEventResponse(
				(event.getActor() == null) ? null : event.getActor().getFullName(),
				(event.getActor() == null) ? null : event.getActor().getEmail(),
				event.getAction(), event.getEntityType(), event.getEntityId(), event.getDetails(), event.getCreatedAt());
	}

}
