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
 * One job-order slice of an {@link InventoryItem}'s quantity (REQ-INV-027), independent of the
 * mission split; each dimension's total must stay at or below the entry's amount.
 *
 * <p>The owning entry's {@code @Version} is the concurrency token for the whole split. Deleting the
 * entry or the job order removes the slice; the entry survives a job-order deletion.
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
  private Double amount;

  /**
   * Whether the stock earmarked to this job order has been delivered (REQ-INV-027), tracked per
   * order so one entry can be delivered for one order and open for another.
   */
  @Builder.Default
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
