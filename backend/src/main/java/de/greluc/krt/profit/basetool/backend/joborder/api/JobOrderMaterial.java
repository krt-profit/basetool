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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Job Order Material JPA entity. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order_material")
public class JobOrderMaterial extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_id", nullable = false)
  private JobOrder jobOrder;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id", nullable = false)
  private Material material;

  /** The quality tier the line asks for; its floor decides which stock counts toward the line. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "quality_tier_id", nullable = false)
  private QualityTier qualityTier;

  /** The requested amount; handovers never change it, only an order edit does (ADR-0400). */
  @Column(nullable = false)
  private Double amount;

  /**
   * The handed-over amount credited to this line, the sum of its handover credits; never above
   * {@link #amount}.
   */
  @Column(name = "credited_amount", nullable = false)
  @Setter(AccessLevel.NONE)
  @Builder.Default
  private double creditedAmount = 0.0;

  /**
   * Returns the need still open on this line.
   *
   * @return the requested amount less the credited amount, never negative; 0 without an amount
   */
  public double remainingAmount() {
    return amount == null
        ? 0.0
        : Math.max(0.0, ScuAmounts.roundToScuScale(amount - creditedAmount));
  }

  /**
   * Adds a handover credit to this line's credited amount.
   *
   * @param credit the credited quantity, positive and at most the remaining amount
   */
  public void credit(double credit) {
    creditedAmount = ScuAmounts.roundToScuScale(creditedAmount + credit);
  }

  /**
   * Rounds the requested and credited amounts to SCU scale (three decimals, {@code HALF_UP}) on
   * every insert and update; a no-op for whole {@code PIECE} amounts.
   *
   * @see ScuAmounts#roundToScuScale(Double)
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    if (amount != null) {
      amount = ScuAmounts.roundToScuScale(amount.doubleValue());
    }
    creditedAmount = ScuAmounts.roundToScuScale(creditedAmount);
  }
}
