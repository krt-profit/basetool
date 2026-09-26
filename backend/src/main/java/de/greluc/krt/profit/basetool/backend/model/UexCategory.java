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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Reference row from UEX's {@code /categories} endpoint. Drives {@code UexItemSyncService}'s walk
 * through {@code /items?id_category=<n>} and maps its {@link #getSection()} / {@link #getName()}
 * pair to a {@link GameItemKind}. The primary key is UEX's stable integer id.
 */
@Entity
@Table(name = "uex_category")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UexCategory extends AbstractEntity<Integer> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  private Integer id;

  /** {@code "item"} or {@code "vehicle"} — drives the kind derivation in the sync. */
  @Column(nullable = false, length = 16)
  private String type;

  /**
   * Coarse grouping displayed in UEX's UI (e.g. {@code "Armor"}, {@code "Vehicle Weapons"}, {@code
   * "Systems"}). Indexed on the DB side; consumed by the item-sync kind derivation.
   */
  @Column(nullable = false, length = 64)
  private String section;

  /** Subcategory display name (e.g. {@code "Helmets"}, {@code "Torso"}, {@code "Coolers"}). */
  @Column(nullable = false, length = 128)
  private String name;

  /**
   * UEX's {@code is_game_related} flag. Categories with this flag false are reference-only and
   * should NOT drive an item walk (e.g. internal taxonomy buckets).
   */
  @Column(name = "is_game_related", nullable = false)
  private Boolean isGameRelated;

  /** UEX's {@code is_mining} flag. Used by mining-specific UI filters; not consumed by R2. */
  @Column(name = "is_mining", nullable = false)
  private Boolean isMining;

  /** Timestamp of the most recent successful UEX sync touch. */
  @Column(name = "uex_synced_at")
  private Instant uexSyncedAt;

  /**
   * Timestamp of the first sync run in which UEX no longer returned this category. Soft-delete
   * marker; cleared on the next sync that sees it again.
   */
  @Column(name = "uex_deleted_at")
  private Instant uexDeletedAt;
}
