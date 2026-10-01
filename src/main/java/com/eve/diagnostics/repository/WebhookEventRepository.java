package com.eve.diagnostics.repository;

import com.eve.diagnostics.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Note what is missing: there is no findByEventId.
 *
 * <p>Duplicate detection deliberately does not use a lookup query. A
 * "SELECT then INSERT" is racy - two concurrent requests both see no row and
 * both insert. The UNIQUE constraint on event_id decides the winner instead, and
 * the duplicate case is detected from the constraint violation.
 */
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
}