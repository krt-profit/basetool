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
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A minimum stock level of one org unit's pool for a material with an optional quality tier or for
 * a game item (REQ-INV-067). Exactly one of {@link #material} and {@link #gameItem} is set. Its own
 * version is the only lock it takes; it never touches a Lager row.
 */
@TenantScoped("orgUnit")
@Entity
@Table(name = "inventory_stock_level")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class StockLevel extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The unit whose pool the level watches; fixed after creation. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "org_unit_id", nullable = false, updatable = false)
  @ToString.Exclude
  private OrgUnit orgUnit;

  /** The watched material, {@code null} for a game-item level; fixed after creation. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id", updatable = false)
  @ToString.Exclude
  @Nullable
  private Material material;

  /** The watched game item, {@code null} for a material level; fixed after creation. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id", updatable = false)
  @ToString.Exclude
  @Nullable
  private GameItem gameItem;

  /**
   * The tier whose floor the counted stock must reach, {@code null} for any quality and always for
   * a game item.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "quality_tier_id")
  @ToString.Exclude
  @Nullable
  private QualityTier qualityTier;

  /** The amount below which the level is low. */
  @Column(name = "minimum_amount", nullable = false)
  private double minimum;

  /** The amount the level is filled up to; at least the minimum. */
  @Column(name = "target_amount", nullable = false)
  private double target;

  /**
   * The id of the watched catalogue entry, the material or the game item.
   *
   * @return the material id or the game item id
   */
  public @NotNull UUID subjectId() {
    return material != null ? material.getId() : Objects.requireNonNull(gameItem).getId();
  }

  /**
   * The name of the watched catalogue entry.
   *
   * @return the material name or the game item name
   */
  public @NotNull String subjectName() {
    return material != null ? material.getName() : Objects.requireNonNull(gameItem).getName();
  }

  /**
   * Whether amounts are counted in whole units: a game item or a piece material.
   *
   * @return {@code true} iff the level takes whole units only
   */
  public boolean wholeUnits() {
    return material == null || material.getQuantityType() == QuantityType.PIECE;
  }
}
