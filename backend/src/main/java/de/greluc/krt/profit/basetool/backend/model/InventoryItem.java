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
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * One warehouse stock row, either a material row ({@link #material} and {@link #quality}) or a game
 * item row ({@link #gameItem}, no quality, whole units) (REQ-INV-029, ADR-0101). Exactly one
 * catalog reference is set.
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  @ToString.Exclude
  private User user;

  /**
   * The commodity this row stocks, or {@code null} for a game-item row (REQ-INV-029). {@code
   * optional = true} is load-bearing: it keeps Hibernate from turning implicit joins on {@code
   * i.material} into inner joins that drop game-item rows.
   */
  @ManyToOne(optional = true, fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id", nullable = true)
  @ToString.Exclude
  private Material material;

  /**
   * The game item (catalog entry, blueprint output) this row stocks, or {@code null} for a material
   * row (REQ-INV-029, ADR-0101). Game-item rows carry no {@link #quality} and hold positive
   * whole-unit amounts; they follow the PIECE auto-merge rule of REQ-INV-026 and may be allocated
   * only to ITEM job orders requesting this game item (REQ-INV-031).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id", nullable = true)
  @ToString.Exclude
  private GameItem gameItem;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "location_id", nullable = false)
  @ToString.Exclude
  private Location location;

  /**
   * Stock quality (0–1000) of a material row; always {@code null} for a game-item row (items have
   * no quality dimension, REQ-INV-029). The pairing is DB-enforced via {@code
   * chk_inventory_item_quality_by_kind} (V220).
   */
  @Min(0)
  @Max(1000)
  @Column(nullable = true)
  private Integer quality;

  @Min(0)
  @Column(nullable = false)
  private Double amount;

  @Column(nullable = false)
  private Boolean personal = false;

  /**
   * The job-order quantity slices of this entry (REQ-INV-027), written and deleted through it.
   * Their sum must stay at or below {@link #amount}; ordered by creation for deterministic
   * projections.
   */
  @OneToMany(mappedBy = "inventoryItem", cascade = CascadeType.ALL, orphanRemoval = true)
  @BatchSize(size = 100)
  @OrderBy("createdAt ASC, id ASC")
  @ToString.Exclude
  private List<InventoryJobOrderAllocation> jobOrderAllocations = new ArrayList<>();

  /**
   * The mission quantity slices of this entry (Variante C, REQ-INV-027) — the mission counterpart
   * of {@link #jobOrderAllocations}, split independently; the sum of the slice amounts must stay ≤
   * {@link #amount}. Ordered by creation (id as tiebreaker) so the soak-compat single-value mission
   * projections in {@code InventoryItemMapper} are deterministic rather than bag-order dependent.
   */
  @OneToMany(mappedBy = "inventoryItem", cascade = CascadeType.ALL, orphanRemoval = true)
  @BatchSize(size = 100)
  @OrderBy("createdAt ASC, id ASC")
  @ToString.Exclude
  private List<InventoryMissionAllocation> missionAllocations = new ArrayList<>();

  @Column(name = "note", length = 1000)
  private String note;

  /**
   * The org unit whose physical stock this row represents, or {@code null} for an ownerless
   * personal item visible only to its {@link #user} (and admins). Stamped via {@code
   * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id", nullable = true)
  @ToString.Exclude
  private OrgUnit owningOrgUnit;

  /**
   * Rounds {@link #amount} to three decimals before every insert and update, so no internally
   * computed {@code double} value is stored with floating-point noise. Applied to every quantity
   * type, since it is a no-op for whole-number amounts.
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    amount = roundToScuScale(amount);
  }

  /**
   * Rounds an SCU amount to three decimals with {@link RoundingMode#HALF_UP}, leaving {@code null}
   * untouched; the rounding {@link #roundAmountToScuScale()} applies.
   *
   * @param value the raw amount, possibly carrying floating-point noise
   * @return {@code value} rounded to three decimals, or {@code null} when {@code value} is {@code
   *     null}
   */
  @Contract("null -> null")
  @Nullable
  public static Double roundToScuScale(Double value) {
    if (value == null) {
      return null;
    }
    return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
  }
}
