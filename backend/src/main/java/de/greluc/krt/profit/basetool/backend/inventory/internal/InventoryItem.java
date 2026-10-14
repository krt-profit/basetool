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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.QualityValue;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.TenantScoped;
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
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * One warehouse stock row, either a material row ({@link #material} and {@link #quality}) or a game
 * item row ({@link #gameItem}, no quality, whole units) (REQ-INV-029, ADR-0101). Exactly one
 * catalog reference is set.
 */
@TenantScoped("owningOrgUnit")
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem extends AbstractEntity<UUID> implements StockRow {

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
  @Nullable
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
  @Nullable
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
  @QualityValue
  @Column(nullable = true)
  @Nullable
  private Integer quality;

  @Min(0)
  @Column(nullable = false)
  @Setter(onParam_ = @__(@Nullable))
  private Double amount;

  @Column(nullable = false)
  private Boolean personal = false;

  /**
   * Whether this stock is marked „gestohlen" (stolen cargo); part of the stock identity, so a
   * stolen and a legitimate row never share a stack or merge (REQ-INV-053).
   */
  @Column(nullable = false)
  private Boolean stolen = false;

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

  /**
   * The donation quantity slices of this entry (REQ-INV-057): what its owner pledged as a donation
   * and has not yet handed over. Split independently of the other two dimensions; the sum must stay
   * ≤ {@link #amount}.
   */
  @OneToMany(mappedBy = "inventoryItem", cascade = CascadeType.ALL, orphanRemoval = true)
  @BatchSize(size = 100)
  @OrderBy("createdAt ASC, id ASC")
  @ToString.Exclude
  @Getter(AccessLevel.NONE)
  private List<InventoryDonationAllocation> donationAllocations = new ArrayList<>();

  /**
   * Returns the donation slices as a read-only view; a slice is added or removed only through
   * {@link #addDonationAllocation} and {@link #removeDonationAllocation}.
   *
   * @return the unmodifiable view of the slices, oldest first
   */
  @UnmodifiableView
  public List<InventoryDonationAllocation> getDonationAllocations() {
    return Collections.unmodifiableList(donationAllocations);
  }

  /**
   * Adds a donation slice to this entry.
   *
   * @param slice the slice, already pointing at this entry
   */
  public void addDonationAllocation(InventoryDonationAllocation slice) {
    donationAllocations.add(slice);
  }

  /**
   * Removes a donation slice from this entry; the orphan removal deletes its row.
   *
   * @param slice the slice
   * @return {@code true} when the entry held the slice
   */
  public boolean removeDonationAllocation(InventoryDonationAllocation slice) {
    return donationAllocations.remove(slice);
  }

  @Column(name = "note", length = 1000)
  @Nullable
  private String note;

  /**
   * The org unit whose physical stock this row represents, or {@code null} for an ownerless
   * personal item visible only to its {@link #user} (and admins). Stamped via {@code
   * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id", nullable = true)
  @ToString.Exclude
  @Nullable
  private OrgUnit owningOrgUnit;

  /**
   * Rounds {@link #amount} to three decimals before every insert and update, so no internally
   * computed {@code double} value is stored with floating-point noise. Applied to every quantity
   * type, since it is a no-op for whole-number amounts.
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    if (amount != null) {
      amount = ScuAmounts.roundToScuScale(amount.doubleValue());
    }
  }

  @Override
  public double jobOrderReserved() {
    return InventoryAllocations.sumJobOrder(this);
  }

  @Override
  public double missionReserved() {
    return InventoryAllocations.sumMission(this);
  }

  @Override
  public double donationReserved() {
    return InventoryAllocations.sumDonation(this);
  }

  @Override
  public boolean isEarmarkedForJobOrder(@NotNull UUID jobOrderId) {
    return InventoryAllocations.jobOrderSlice(this, jobOrderId) != null;
  }

  @Override
  public double jobOrderSliceAmount(@NotNull UUID jobOrderId) {
    InventoryJobOrderAllocation slice = InventoryAllocations.jobOrderSlice(this, jobOrderId);
    return slice == null || slice.getAmount() == null ? 0.0 : slice.getAmount();
  }
}
