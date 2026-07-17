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

/**
 * Inventory Item JPA entity — one warehouse stock row, discriminated by catalog kind (REQ-INV-029,
 * ADR-0101): a <em>material</em> row carries {@link #material} + {@link #quality}, a <em>game
 * item</em> row carries {@link #gameItem} with {@code quality == null} and whole-unit amounts.
 * Exactly one of the two catalog references is set (DB CHECK {@code
 * chk_inventory_item_catalog_xor}, V220).
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

  // @ToString.Exclude on every LAZY association so a call to toString() outside
  // of a Hibernate session (e.g. from a log statement after the transaction
  // has committed) does not trigger LazyInitializationException. Matches the
  // pattern already used in Mission / Operation / RefineryOrder.
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  @ToString.Exclude
  private User user;

  /**
   * The commodity this row stocks, or {@code null} for a game-item row (REQ-INV-029). Nullable
   * since V220 — note the {@code optional = true} mapping is load-bearing: Hibernate uses it to
   * decide whether an implicit path join may be optimised to an inner join, so flipping it back
   * would silently drop NULL-material rows from every query that navigates {@code i.material}.
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
  private Double amount; // SCU

  @Column(nullable = false)
  private Boolean personal = false;

  /**
   * The job-order quantity slices of this entry (Variante C, REQ-INV-027) — an entry may earmark
   * parts of its stock to several job orders at once, each with its own amount, split independently
   * of {@link #missionAllocations}. Cascade + orphan-removal so the slices are written and deleted
   * through the entry; the sum of the slice amounts must stay ≤ {@link #amount} (enforced in the
   * service, REQ-INV-027). Ordered by creation (id as tiebreaker) so the soak-compat single-value
   * projections in {@code InventoryItemMapper} (first earmark) are deterministic rather than
   * dependent on the bag's DB row order.
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
   * Org-unit owner of this inventory item (the org unit whose physical stock this row represents),
   * or {@code null} for an <em>ownerless personal</em> item — one recorded by a user who belongs to
   * no Staffel/SK. Such an item is attributable solely through {@link #user} and is
   * visible/editable only by that user (plus admins in all-scopes mode); it never surfaces in an
   * org unit's Lager-View. Callers stamp this field via {@code
   * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}. V132 dropped the {@code NOT NULL}
   * constraint V102 had added, which is why the column — and therefore this {@code @JoinColumn} —
   * is nullable.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id", nullable = true)
  @ToString.Exclude
  private OrgUnit owningOrgUnit;

  /**
   * Rounds {@link #amount} to SCU storage precision (three decimals) before every {@code INSERT}
   * and {@code UPDATE}.
   *
   * <p>{@code amount} is a {@code double}, so server-side arithmetic that sums or subtracts
   * fractional quantities (the refinery store-into-inventory merge in {@code RefineryOrderService},
   * transfers, handovers) can land on a neighbouring binary value whose shortest decimal form
   * carries more than three fractional digits — e.g. summing refinery yields produced the stored
   * value {@code 37.160000000000004}. The inbound DTO validator {@code
   * ValidQuantityAmountValidator} rejects such precision on user input, but it never sees these
   * internally computed amounts. This callback is the single persistence chokepoint every write
   * path flushes through, so rounding here guarantees no row is ever stored with more than three
   * decimals, no matter which service produced the value.
   *
   * <p>Rounding is unconditional rather than gated on {@link QuantityType#SCU}: {@code PIECE}
   * amounts are whole numbers, so three-decimal rounding is a no-op for them, and reading {@link
   * #material} inside a lifecycle callback would force a lazy-load of the proxy on every flush.
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    amount = roundToScuScale(amount);
  }

  /**
   * Rounds an SCU amount to three decimals using {@link RoundingMode#HALF_UP} (commercial
   * rounding), leaving {@code null} untouched.
   *
   * <p>This is the canonical SCU-precision rounding that {@link #roundAmountToScuScale()} applies
   * at the persistence boundary. It is also reused by write paths that compute an amount through
   * {@code double} arithmetic <em>before</em> it reaches that hook — e.g. the refinery
   * store-into-inventory merge in {@code RefineryOrderService} — so the in-memory value is already
   * clean. That is defence in depth: the lifecycle callback remains the guarantee, but the producer
   * no longer hands a dirty value around in the meantime.
   *
   * @param value the raw amount, possibly carrying floating-point noise beyond three decimals
   * @return {@code value} rounded to three decimals, or {@code null} when {@code value} is {@code
   *     null}
   */
  public static Double roundToScuScale(Double value) {
    if (value == null) {
      return null;
    }
    return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
  }
}
