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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * Append-only audit row of a single admin approval decision on a registration (epic #720, Track 1).
 *
 * <p>One row is written per approve/reject/link action: it records which user was decided on, the
 * {@link ApprovalDecision}, the deciding admin, and an optional free-text reason (typically on
 * rejection). Never updated after creation — the {@code created_at} timestamp is the decision time.
 */
@Entity
@Table(name = "user_approval_event")
@Getter
@Setter
@NoArgsConstructor
public class UserApprovalEvent extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ApprovalDecision decision;

  @Nullable
  @Column(columnDefinition = "TEXT")
  private String reason;

  @Nullable
  @Column(name = "decided_by_id")
  private UUID decidedById;

  /**
   * Creates an audit row for a decision.
   *
   * @param userId the user whose registration was decided
   * @param decision approve or reject
   * @param reason optional free-text reason (typically on rejection); may be {@code null}
   * @param decidedById the deciding admin's id; may be {@code null} for a system action
   */
  public UserApprovalEvent(
      UUID userId, ApprovalDecision decision, @Nullable String reason, @Nullable UUID decidedById) {
    this.userId = userId;
    this.decision = decision;
    this.reason = reason;
    this.decidedById = decidedById;
  }
}
