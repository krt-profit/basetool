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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A single job-order slice of an {@link InventoryItem}'s quantity — one row of the "getrennte
 * Mengen-Aufteilung" (Variante C, REQ-INV-027): an entry may earmark part of its stock to several
 * job orders at once, each with its own {@link #amount}. Independent of the mission split; the two
 * dimensions are validated separately (each dimension's Σ amount must stay ≤ the owning entry's
 * amount). The owning {@link InventoryItem}'s {@code @Version} is the concurrency token for the
 * whole split — this child carries a {@code @Version} of its own only via {@link AbstractEntity}
 * and it is never echoed to the client.
 *
 * <p>Both foreign keys are {@code ON DELETE CASCADE} (V217): deleting the entry removes its
 * allocations, and deleting the job order removes the allocation while the entry survives as
 * (partially) unassigned stock — the successor to the former {@code unlinkJobOrder} null-out.
 */
@Entity
@Table(
    name = "inventory_item_job_order_allocation",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_inv_job_order_alloc",
            columnNames = {"inventory_item_id", "job_order_id"}))
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryJobOrderAllocation extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "inventory_item_id", nullable = false)
  @ToString.Exclude
  private InventoryItem inventoryItem;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_id", nullable = false)
  @ToString.Exclude
  private JobOrder jobOrder;

  @Min(0)
  @Column(nullable = false)
  private Double amount; // SCU

  /**
   * Whether the stock earmarked to this job order has been delivered (Variante A, REQ-INV-027) —
   * the per-order successor to the former entry-level {@code InventoryItem.delivered}. An entry
   * that serves several orders can be delivered for one and still open for another, which one
   * entry-level flag could not express. Mission slices carry no delivered marker (a mission has no
   * "delivery").
   */
  @Column(nullable = false)
  private Boolean delivered = false;

  /**
   * Rounds {@link #amount} to SCU storage precision (three decimals) before every {@code INSERT}
   * and {@code UPDATE}, mirroring {@link InventoryItem#roundAmountToScuScale()} so an allocation
   * never stores more fractional digits than the entry it splits.
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    amount = InventoryItem.roundToScuScale(amount);
  }
}
