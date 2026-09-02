/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * One immutable bank audit-trail row (epic #556, REQ-BANK-012), persisted in the {@code
 * bank_audit_event} table created by Flyway V154 and readable only by admins.
 *
 * <p>Insert-only event log modeled after {@link ExternalSyncReport}: no {@code @Version}, no
 * updates, {@link #occurredAt} is the single timestamp. Every bank mutation appends exactly one row
 * in the same transaction as the business write — an audit failure fails the mutation, so the trail
 * has no silent gaps. Reference columns are plain UUIDs (no JPA relations): audit rows must outlive
 * every referenced aggregate, and the admin viewer renders the deletion-proof {@link #actorHandle}
 * snapshot rather than joining live rows.
 *
 * <p>This table is business data, not logging — the observability rule (never log names, emails or
 * tokens to the <em>log stream</em>) is unaffected and still applies to all bank code.
 */
@Entity
@Table(name = "bank_audit_event")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankAuditEvent {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Instant the audited mutation happened (UTC); stamped by {@code BankAuditService}. */
  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /**
   * The acting user's id (JWT {@code sub}); the database FK is {@code ON DELETE SET NULL}, the
   * {@link #actorHandle} snapshot keeps the row attributable afterwards.
   */
  @Nullable
  @Column(name = "actor_user_id")
  private UUID actorUserId;

  /** Denormalized actor handle snapshot — the trail must survive user deletion (REQ-BANK-012). */
  @Column(name = "actor_handle", nullable = false)
  private String actorHandle;

  /** What happened; the enum is the source of truth (no DB CHECK, V113 precedent). */
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 40)
  private BankAuditEventType eventType;

  /** The affected {@link BankAccount} id, when the event concerns one account. */
  @Nullable
  @Column(name = "account_id")
  private UUID accountId;

  /** The created {@link BankTransaction} id for booking/reversal/wipe events. */
  @Nullable
  @Column(name = "transaction_id")
  private UUID transactionId;

  /** The affected user for grant and holder events (the grantee / the holder's linked user). */
  @Nullable
  @Column(name = "target_user_id")
  private UUID targetUserId;

  /**
   * Compact human-readable details payload — amounts, holder handles, before/after grant flags,
   * export parameters. Free-form text; the admin viewer shows it verbatim.
   */
  @Nullable
  @Column(columnDefinition = "TEXT")
  private String details;

  /**
   * Which client software the mutation came through — the token's {@code azp} mapped onto the
   * bounded known-client vocabulary (REQ-AUDIT-005, GHSA-2vq5-8p8w-5r64): a known client id
   * verbatim, {@code other} for an unrecognised one, {@code none} for a caller with no token or a
   * token carrying no {@code azp}.
   *
   * <p>Bounded rather than verbatim because this table is evidence: a client-chosen string is the
   * one kind of value that must not be able to write itself into it. {@code azp} is signed by
   * Keycloak and unsettable by the client, so recording it adds no trust that {@code
   * IngestGatewayProperties} does not already place in it.
   *
   * <p>{@code null} on rows written before V238 means <strong>not recorded</strong> — and, unlike
   * {@link AuditEvent#getClientId()}, <em>not</em> "unambiguous anyway": the bank realm roles have
   * been on the mobile client's scope since it was provisioned, so a bank row was reachable from
   * two clients before this column existed. A null here must never be read as "the web frontend".
   */
  @Nullable
  @Column(name = "client_id", length = 60)
  private String clientId;
}
