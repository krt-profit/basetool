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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/** Job Order Handover Item JPA entity. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order_handover_item")
public class JobOrderHandoverItem extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_handover_id", nullable = false)
  private JobOrderHandover jobOrderHandover;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id", nullable = false)
  private Material material;

  @Column(nullable = false)
  @Nullable
  private Integer quality;

  @Column(nullable = false)
  private Double amount;

  @Column(name = "location_name")
  @Nullable
  private String locationName;

  /** Whether the handed-over stock was marked „gestohlen" when it left the Lager (REQ-INV-053). */
  @Builder.Default
  @Column(nullable = false)
  private Boolean stolen = false;

  /**
   * Rounds the delivered {@code amount} to SCU scale (three decimals, {@code HALF_UP}) on insert
   * and update; a no-op for whole {@code PIECE} amounts.
   *
   * @see ScuAmounts#roundToScuScale(Double)
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    amount = ScuAmounts.roundToScuScale(amount);
  }
}
