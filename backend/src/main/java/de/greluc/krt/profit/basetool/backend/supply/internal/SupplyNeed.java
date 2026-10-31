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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriority;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OptimisticLock;
import org.jetbrains.annotations.Nullable;

/**
 * A raising unit's demand for a material or a game item (REQ-SUP-003): the catalogue entry, the
 * amount and the quality tier, the priority and, when the unit allows buying, the reason and the
 * maximum unit price (REQ-SUP-004). Exactly one of {@link #material} and {@link #gameItem} is set.
 */
@Entity
@DynamicUpdate
@Table(name = "supply_need")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class SupplyNeed extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The raising unit. */
  @Column(name = "org_unit_id", nullable = false)
  private UUID orgUnitId;

  /** The material, or {@code null} for a game-item need. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id")
  @ToString.Exclude
  @Nullable
  private Material material;

  /** The game item, or {@code null} for a material need. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id")
  @ToString.Exclude
  @Nullable
  private GameItem gameItem;

  /** The tier a donated or bought material must reach; {@code null} for a game item. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "quality_tier_id")
  @ToString.Exclude
  @Nullable
  private QualityTier qualityTier;

  /** The amount asked for, whole units for pieces. */
  @Column(nullable = false)
  private Double amount;

  /** What the credited donations and accepted deliveries cover together. */
  @OptimisticLock(excluded = true)
  @Column(name = "fulfilled_amount", nullable = false)
  private Double fulfilledAmount = 0.0;

  /** The part of {@link #fulfilledAmount} that came from accepted purchase deliveries. */
  @OptimisticLock(excluded = true)
  @Column(name = "purchased_amount", nullable = false)
  private Double purchasedAmount = 0.0;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private NeedPriority priority = NeedPriority.NORMAL;

  /** Whether the raising unit allows the need to be bought. */
  @Column(nullable = false)
  private boolean purchasable;

  @Enumerated(EnumType.STRING)
  @Column(name = "purchase_reason", length = 32)
  @Nullable
  private PurchaseReason purchaseReason;

  /** The highest whole-aUEC unit price a deal may pay, required when purchasable. */
  @Column(name = "max_unit_price")
  @Nullable
  private Long maxUnitPrice;

  /** The raising unit's job order the need feeds, a plain id reference. */
  @Column(name = "job_order_id")
  @Nullable
  private UUID jobOrderId;

  /** When the unit was told that the linked order covers the need's bucket (REQ-SUP-024). */
  @OptimisticLock(excluded = true)
  @Column(name = "order_covered_notified_at")
  @Nullable
  private Instant orderCoveredNotifiedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private NeedStatus status = NeedStatus.OPEN;

  /** The member who raised the need. */
  @Column(name = "created_by")
  @Nullable
  private UUID createdBy;

  /** Rounds the amounts to the storage scale before every insert and update. */
  @PrePersist
  @PreUpdate
  void roundAmounts() {
    amount = amount == null ? null : ScuAmounts.roundToScuScale(amount);
    fulfilledAmount = ScuAmounts.roundToScuScale(fulfilledAmount);
    purchasedAmount = ScuAmounts.roundToScuScale(purchasedAmount);
  }

  /**
   * Returns what is still missing.
   *
   * @return the amount minus the fulfilled amount, never below zero
   */
  public double openAmount() {
    return Math.max(0.0, ScuAmounts.roundToScuScale(amount - fulfilledAmount));
  }
}
