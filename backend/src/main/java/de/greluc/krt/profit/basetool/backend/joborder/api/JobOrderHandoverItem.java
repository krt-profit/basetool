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
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
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
   * The requirement lines this delivery was credited to, in booking order; together with {@link
   * #surplusAmount} they account for the whole {@link #amount} (ADR-0400).
   */
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "job_order_handover_credit",
      joinColumns = @JoinColumn(name = "job_order_handover_item_id"))
  @Setter(AccessLevel.NONE)
  @Builder.Default
  private List<JobOrderHandoverCredit> credits = new ArrayList<>();

  /** The delivered amount beyond every eligible line's remaining need, credited to no line. */
  @Column(name = "surplus_amount", nullable = false)
  @Builder.Default
  private double surplusAmount = 0.0;

  /**
   * Credits part of this delivery to a requirement line and raises the line's credited amount.
   *
   * @param line the credited line of the same order and material
   * @param credit the credited quantity, positive and at most the line's remaining amount
   */
  public void credit(@NotNull JobOrderMaterial line, double credit) {
    credits.add(new JobOrderHandoverCredit(line.getId(), credit));
    line.credit(credit);
  }

  /**
   * Rounds the delivered and surplus amounts to SCU scale (three decimals, {@code HALF_UP}) on
   * insert and update; a no-op for whole {@code PIECE} amounts.
   *
   * @see ScuAmounts#roundToScuScale(Double)
   */
  @PrePersist
  @PreUpdate
  void roundAmountToScuScale() {
    if (amount != null) {
      amount = ScuAmounts.roundToScuScale(amount.doubleValue());
    }
    surplusAmount = ScuAmounts.roundToScuScale(surplusAmount);
  }
}
