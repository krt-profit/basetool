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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The "paid out" flag of one participant of an operation, toggled by mission managers or higher.
 *
 * <p>A missing row means {@code paid_out = false}. {@code participant_key} uses the key format of
 * {@code OperationPayoutService.getOperationPayouts} (user UUID, or {@code "guest_<name>"}).
 */
@Entity
@Table(
    name = "operation_payout_status",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_operation_payout_status_operation_participant",
            columnNames = {"operation_id", "participant_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"operation", "paidOutByUser"})
public class OperationPayoutStatus extends AbstractEntity<UUID> {

  /**
   * Primary key, generated server-side by Hibernate (the row never carries a client-supplied id).
   *
   * @return the entity's UUID identifier
   */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The operation this status belongs to. {@code FetchType.LAZY} because the read paths typically
   * look up many rows by {@code operation_id} and never need to materialize the full {@link
   * Operation} aggregate (which would otherwise hydrate missions, participants and refinery
   * orders).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "operation_id", nullable = false)
  private Operation operation;

  /**
   * Opaque key identifying the participant within the operation's payout breakdown — real user UUID
   * stringified (e.g. {@code "11111111-1111-..."}), or {@code "guest_<name>"} for an
   * unauthenticated guest participant. Bound by a unique constraint together with {@code
   * operation_id}.
   */
  @Column(name = "participant_key", nullable = false, length = 255)
  private String participantKey;

  /**
   * Whether the participant has been paid out. Defaults to {@code false}; toggled by the {@link
   * de.greluc.krt.profit.basetool.backend.service.OperationPayoutService#setPayoutStatus} entry
   * point.
   */
  @Column(name = "paid_out", nullable = false)
  private boolean paidOut;

  /**
   * Timestamp of the most recent transition that set {@code paid_out = true}. Kept as the last-set
   * value when the flag is toggled back to {@code false} so the historical "was paid" timestamp is
   * not lost — callers that want the live "currently paid?" answer must read {@link #paidOut}.
   */
  @Column(name = "paid_out_at")
  private Instant paidOutAt;

  /**
   * The user (mission manager / officer / admin) who most recently flipped the flag to paid. Lazy
   * because the audit display only resolves a name when the row is rendered, not when it is loaded
   * for the per-participant lookup map.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "paid_out_by_user_id")
  private User paidOutByUser;
}
