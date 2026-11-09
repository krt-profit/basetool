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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.TenantScoped;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Formula;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Refinery Order JPA entity. */
@TenantScoped("owningOrgUnit")
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RefineryOrder extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Setter(onParam_ = @__(@Nullable))
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id", nullable = false)
  @ToString.Exclude
  private User owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "location_id", nullable = false)
  @ToString.Exclude
  private Location location;

  /** The linked mission, or {@code null}; the mission module resolves its reference. */
  @Column(name = "mission_id")
  @Nullable
  private UUID missionId;

  /**
   * Carried only by the transient order of an update request whose mission reference has no id: the
   * order's current link is then left as it is. Never persisted.
   */
  @Transient private boolean missionReferenceWithoutId;

  @Nullable private Instant startedAt;

  /** When the order-ready notice was raised, or {@code null} (REQ-REFINERY-023). */
  @Column(name = "ready_notified_at")
  @Nullable
  private Instant readyNotifiedAt;

  @Nullable @PositiveOrZero private Long durationMinutes;

  /**
   * End of the run, {@code startedAt + durationMinutes}, computed by the database so the order
   * lists can filter and sort on it (REQ-REFINERY-019); {@code null} when either part is unset.
   */
  @Formula("(started_at + make_interval(0, 0, 0, 0, 0, 0, duration_minutes * 60))")
  @Setter(AccessLevel.NONE)
  @Nullable
  private Instant endsAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "refining_method_id")
  @ToString.Exclude
  @Nullable
  private RefiningMethod refiningMethod;

  /**
   * Order costs. Must be >= 0. Optional: 0 is treated as "not set" on save and persisted as {@code
   * null}. The profit calculation treats {@code null} as 0.
   */
  @Nullable @PositiveOrZero private Double expenses;

  /**
   * Other costs in addition to the regular {@link #expenses}. Must be >= 0. Optional: 0 is treated
   * as "not set" on save and persisted as {@code null}.
   */
  @Nullable @PositiveOrZero private Double otherExpenses;

  /**
   * Revenue from selling raw ores ("Ore Sales"). Must be >= 0. Optional: 0 is treated as "not set"
   * on save and persisted as {@code null}.
   */
  @Nullable @PositiveOrZero private Double oreSales;

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

  /**
   * When the order's output was booked into inventory, or {@code null} while it has not been. Set
   * only by the store operation and never cleared; a stored order can be neither stored again nor
   * have its status changed by an update.
   */
  @Nullable private Instant storedAt;

  /**
   * Optimistic-lock counter of the game-data section: location, method, timer, status, goods and
   * the store marker (REQ-REFINERY-029). A business counter beside the row {@code @Version}, raised
   * by {@link RefineryOrderSection#advance}; on an update request it carries the caller's echo.
   */
  @Column(name = "game_data_version", nullable = false)
  @Nullable
  private Long gameDataVersion = 0L;

  /**
   * Optimistic-lock counter of the finances section: {@link #expenses}, {@link #otherExpenses} and
   * {@link #oreSales} (REQ-REFINERY-029). Raised and echoed like {@link #gameDataVersion}.
   */
  @Column(name = "finances_version", nullable = false)
  @Nullable
  private Long financesVersion = 0L;

  @OneToMany(mappedBy = "refineryOrder", cascade = CascadeType.ALL, orphanRemoval = true)
  @ToString.Exclude
  private Set<@Valid RefineryGood> goods = new HashSet<>();

  /**
   * Org-unit owner of this refinery order, or {@code null} for an ownerless personal order raised
   * by a user in no Staffel/SK. Such an order is visible and editable only by its {@link #owner}
   * (plus admins in all-scopes mode). Stamped via {@code
   * OrgUnitStampingService.resolveOrgUnitForPickerOutputNullable}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id", nullable = true)
  @ToString.Exclude
  @Nullable
  private OrgUnit owningOrgUnit;

  /** Who created the order, who changed it last and who reported its pickup (REQ-REFINERY-027). */
  @Embedded @NotNull
  private RefineryOrderProvenance provenance = RefineryOrderProvenance.createdInWeb(Instant.now());
}
