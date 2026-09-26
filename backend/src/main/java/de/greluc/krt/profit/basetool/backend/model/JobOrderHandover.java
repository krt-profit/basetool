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

/** Job Order Handover JPA entity. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order_handover")
public class JobOrderHandover extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_id", nullable = false)
  private JobOrder jobOrder;

  @Column(name = "handover_time", nullable = false)
  private Instant handoverTime;

  @Column(name = "recipient_handle", nullable = false)
  private String recipientHandle;

  @Column(name = "recipient_squadron")
  private String recipientSquadron;

  /**
   * Audit field: the user who executed the handover, stamped from the current JWT principal at
   * create time; {@code null} when not recorded. May belong to a different squadron than the order.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "executing_user_id")
  private User executingUser;

  /**
   * Audit field: snapshot of the executing user's squadron at handover time, so later squadron
   * changes do not rewrite the audit trail; {@code null} when the user had no squadron.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "executing_squadron_id")
  private Squadron executingSquadron;

  @OneToMany(
      mappedBy = "jobOrderHandover",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderHandoverItem> items = new HashSet<>();

  /** Adds a handover item and keeps the bidirectional back-reference in sync. */
  public void addItem(JobOrderHandoverItem item) {
    items.add(item);
    item.setJobOrderHandover(this);
  }
}
