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
 * One immutable activity audit-trail row of the audited areas (REQ-AUDIT-001), readable only by
 * admins.
 *
 * <p>Insert-only: every audited mutation appends exactly one row in the same transaction, so an
 * audit failure fails the mutation. References are plain UUIDs so rows outlive their aggregates;
 * {@link #details} never carries user free text.
 */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Instant the audited mutation happened (UTC); stamped by {@code AuditService}. */
  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /** The functional area this row belongs to; the admin viewer's tab discriminator. */
  @Enumerated(EnumType.STRING)
  @Column(name = "domain", nullable = false, length = 30)
  private AuditDomain domain;

  /** What happened; the enum is the source of truth (no DB CHECK, V113/V154 precedent). */
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 60)
  private AuditEventType eventType;

  /**
   * The acting user's id (JWT {@code sub}); the database FK is {@code ON DELETE SET NULL}, the
   * {@link #actorHandle} snapshot keeps the row attributable afterwards.
   */
  @Nullable
  @Column(name = "actor_user_id")
  private UUID actorUserId;

  /** Denormalized actor handle snapshot — the trail must survive user deletion (REQ-AUDIT-001). */
  @Column(name = "actor_handle", nullable = false)
  private String actorHandle;

  /** The primary affected aggregate's id (inventory row / job order / refinery order / item). */
  @Nullable
  @Column(name = "subject_id")
  private UUID subjectId;

  /**
   * Snapshot of the subject's human-readable label, kept readable after the subject is gone.
   *
   * <p>It can name a person (e.g. a job order's contact handle), so it is treated as personal data
   * and excluded from the Art. 15 export (REQ-SEC-058).
   */
  @Nullable
  @Column(name = "subject_label", length = 255)
  private String subjectLabel;

  /** The affected user for events about a user (assignee, transfer recipient, on-behalf-of). */
  @Nullable
  @Column(name = "target_user_id")
  private UUID targetUserId;

  /**
   * Compact human-readable details payload — amounts, counts, before/after values, export
   * parameters. Free-form text; the admin viewer shows it verbatim. Never carries user free text.
   */
  @Nullable
  @Column(columnDefinition = "TEXT")
  private String details;

  /**
   * The client the mutation came through: the token's {@code azp} mapped onto the bounded
   * known-client vocabulary, {@code other} for an unknown one, {@code none} when there is no token
   * or no {@code azp} (REQ-AUDIT-005).
   *
   * <p>{@code null} only on rows that predate the column; a new row always records a value.
   */
  @Nullable
  @Column(name = "client_id", length = 60)
  private String clientId;
}
