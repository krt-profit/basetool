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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Refinery Order JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class RefineryOrder extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  @ManyToOne
  @JoinColumn(name = "location_id", nullable = false)
  private Location location;

  @ManyToOne
  @JoinColumn(name = "mission_id")
  private Mission mission;

  private Instant startedAt;

  @PositiveOrZero private Long durationMinutes;

  @ManyToOne
  @JoinColumn(name = "refining_method_id")
  private RefiningMethod refiningMethod;

  /**
   * Order costs. Must be >= 0. Optional: 0 is treated as "not set" on save and persisted as {@code
   * null}. The profit calculation treats {@code null} as 0.
   */
  @PositiveOrZero private Double expenses;

  /**
   * Other costs in addition to the regular {@link #expenses}. Must be >= 0. Optional: 0 is treated
   * as "not set" on save and persisted as {@code null}.
   */
  @PositiveOrZero private Double otherExpenses;

  /**
   * Revenue from selling raw ores ("Ore Sales"). Must be >= 0. Optional: 0 is treated as "not set"
   * on save and persisted as {@code null}.
   */
  @PositiveOrZero private Double oreSales;

  /**
   * Computed profit/loss: oreSales - expenses - otherExpenses. May be negative. Not persisted;
   * derived server-side from the raw values.
   */
  @Transient
  public Double getProfit() {
    double sales = oreSales != null ? oreSales : 0d;
    double costs = expenses != null ? expenses : 0d;
    double other = otherExpenses != null ? otherExpenses : 0d;
    return sales - costs - other;
  }

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RefineryOrderStatus status = RefineryOrderStatus.OPEN;

  @OneToMany(mappedBy = "refineryOrder", cascade = CascadeType.ALL, orphanRemoval = true)
  @ToString.Exclude
  private Set<@Valid RefineryGood> goods = new HashSet<>();

  /**
   * Org-unit owner of this refinery order, or {@code null} for an <em>ownerless personal</em> order
   * — one raised by a user who belongs to no Staffel/SK. Such an order is attributable solely
   * through {@link #owner} and is visible/editable only by that owner (plus admins in all-scopes
   * mode); it never surfaces in an org unit's order list. Callers stamp this field via {@code
   * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}. V132 dropped the {@code NOT NULL}
   * constraint V102 had added, which is why the column — and therefore this {@code @JoinColumn} —
   * is nullable.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id", nullable = true)
  @ToString.Exclude
  private OrgUnit owningOrgUnit;
}
