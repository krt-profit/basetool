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

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single fulfilment event on an {@link JobOrderType#ITEM} {@link JobOrder}: the hand-over of
 * finished items to a recipient, itemised in {@link #entries}.
 *
 * <p>Records {@link #executingUser} and an {@link #executingSquadron} snapshot, since a logistician
 * may fulfil an order on behalf of another squadron.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order_item_handover")
public class JobOrderItemHandover extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning item order. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_id", nullable = false)
  private JobOrder jobOrder;

  /** Instant the handover was performed (UTC). */
  @Column(name = "handover_time", nullable = false)
  private Instant handoverTime;

  /** Handle of the recipient the items were handed to. */
  @Column(name = "recipient_handle", nullable = false)
  private String recipientHandle;

  /**
   * Audit field: the authenticated user who executed the handover, stamped at create time from the
   * current JWT principal. {@code null} only if the user account is later deleted (the FK is {@code
   * ON DELETE SET NULL} so the handover record survives the loss of "who did it"). The executing
   * user may belong to a different squadron than the order's owning one (cross-staffel workspace).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "executing_user_id")
  private User executingUser;

  /**
   * Audit field: the squadron the executing user belonged to at handover time, captured as a
   * snapshot so a later squadron change does not retroactively rewrite the audit trail. {@code
   * null} when the executing user had no squadron assigned.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "executing_squadron_id")
  private Squadron executingSquadron;

  /** The individual delivered item-line quantities that make up this handover. */
  @OneToMany(
      mappedBy = "jobOrderItemHandover",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderItemHandoverEntry> entries = new HashSet<>();

  /**
   * Adds a delivered-line entry and keeps the bidirectional back-reference in sync.
   *
   * @param entry the child entry to attach; mutated so its {@code jobOrderItemHandover} back-link
   *     points at this handover.
   */
  public void addEntry(JobOrderItemHandoverEntry entry) {
    entries.add(entry);
    entry.setJobOrderItemHandover(this);
  }
}
