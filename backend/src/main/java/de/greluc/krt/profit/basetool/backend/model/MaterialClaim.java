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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

/**
 * A squadron's claim ("Eintragung") for part of one material bucket {@code (jobOrder, material,
 * qualityRequirement)} on a public Spezialkommando job order.
 *
 * <p>One claim per bucket and claiming org unit. Claims are signal-only and never move inventory;
 * they form an independent aggregate, so changing one never bumps the order's {@code @Version}.
 * Invariants are enforced in {@code MaterialClaimService}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "material_claim")
public class MaterialClaim extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The public SK job order this claim signs up against. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_id", nullable = false)
  private JobOrder jobOrder;

  /** The material the claiming squadron commits to deliver. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id", nullable = false)
  private Material material;

  /**
   * The quality bucket this claim falls into — {@code GOOD} (650+) or {@code NONE} (no floor) —
   * matching the {@code aggregateMaterials()} bucket scheme so a material required in both
   * qualities is claimed separately per quality.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "quality_requirement", nullable = false, length = 8)
  private QualityRequirement qualityRequirement;

  /**
   * The squadron making the claim. Always an {@code OrgUnit} of kind {@code SQUADRON} (validated at
   * the service boundary); modelled as the {@link OrgUnit} base type so the FK targets the shared
   * {@code org_unit} table.
   */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "claiming_org_unit_id", nullable = false)
  private OrgUnit claimingOrgUnit;

  /** The claimed partial quantity, in the material's own unit (SCU fractional vs PIECE whole). */
  @Column(name = "amount", nullable = false)
  private Double amount;

  /**
   * The user who last created/updated the claim, kept for the audit trail. Nullable to tolerate a
   * future system-initiated claim; in practice always set because the create path requires an
   * authenticated LOGISTICIAN+. The claim instant itself is {@code createdAt} from {@link
   * AbstractEntity}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "claimed_by_user_id")
  private User claimedByUser;

  /**
   * Rounds the claimed {@code amount} to SCU scale (three decimals, {@code HALF_UP}) on insert and
   * update; a no-op for whole {@code PIECE} amounts.
   *
   * @see InventoryItem#roundToScuScale(Double)
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    amount = InventoryItem.roundToScuScale(amount);
  }

  /**
   * Renders the claim from scalar fields and associated ids only, so logging it never triggers a
   * lazy load or exposes a user's name or e-mail.
   *
   * @return a PII-free single-line representation
   */
  @NotNull
  @Override
  public String toString() {
    return "MaterialClaim{id="
        + id
        + ", jobOrderId="
        + (jobOrder != null ? jobOrder.getId() : null)
        + ", materialId="
        + (material != null ? material.getId() : null)
        + ", qualityRequirement="
        + qualityRequirement
        + ", claimingOrgUnitId="
        + (claimingOrgUnit != null ? claimingOrgUnit.getId() : null)
        + ", amount="
        + amount
        + ", claimedByUserId="
        + (claimedByUser != null ? claimedByUser.getId() : null)
        + '}';
  }
}
