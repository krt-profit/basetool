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
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.supply.api.DeclineReason;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
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
import org.jetbrains.annotations.Nullable;

/**
 * A member's pledged or received material donation to a receiving unit (REQ-SUP-007 … REQ-SUP-012).
 * Exactly one of {@link #material} and {@link #gameItem} is set. The donor id is cleared on erasure
 * and the donation stays for the books.
 */
@Entity
@Table(name = "supply_donation")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class SupplyDonation extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * The donor, or {@code null} for a donation recorded without one or after the donor's erasure.
   */
  @Column(name = "donor_user_id")
  @Nullable
  private UUID donorUserId;

  /** The unit that confirms and receives the donation. */
  @Column(name = "receiving_org_unit_id", nullable = false)
  private UUID receivingOrgUnitId;

  /** The material, or {@code null} for a game-item donation. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id")
  @ToString.Exclude
  @Nullable
  private Material material;

  /** The game item, or {@code null} for a material donation. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id")
  @ToString.Exclude
  @Nullable
  private GameItem gameItem;

  /** The quality of a material donation, {@code null} for a game item. */
  @Column @Nullable private Integer quality;

  @Column(name = "pledged_amount", nullable = false)
  private Double pledgedAmount;

  /** What the receiving unit confirmed, set once the donation is received. */
  @Column(name = "received_amount")
  @Nullable
  private Double receivedAmount;

  /** The need the donation is credited to, if any. */
  @Column(name = "need_id")
  @Nullable
  private UUID needId;

  /** The Lager row the pledge is earmarked on, a plain id reference. */
  @Column(name = "source_inventory_item_id")
  @Nullable
  private UUID sourceInventoryItemId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private DonationStatus status = DonationStatus.PLEDGED;

  @Enumerated(EnumType.STRING)
  @Column(name = "decline_reason", length = 32)
  @Nullable
  private DeclineReason declineReason;

  /** The member who confirmed the receipt. */
  @Column(name = "received_by")
  @Nullable
  private UUID receivedBy;

  @Column(name = "received_at")
  @Nullable
  private Instant receivedAt;

  /** Rounds the amounts to the storage scale before every insert and update. */
  @PrePersist
  @PreUpdate
  void roundAmounts() {
    pledgedAmount = pledgedAmount == null ? null : ScuAmounts.roundToScuScale(pledgedAmount);
    receivedAmount = receivedAmount == null ? null : ScuAmounts.roundToScuScale(receivedAmount);
  }
}
