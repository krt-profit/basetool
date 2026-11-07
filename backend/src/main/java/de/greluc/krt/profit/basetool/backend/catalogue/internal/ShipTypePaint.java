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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * Links a paint item to a ship type it fits, the catalogue's {@code paints.shipGuids}
 * (REQ-P4K-041); written by the P4K import only, so it has no version column.
 */
@Entity
@Table(name = "ship_type_paint")
@IdClass(ShipTypePaint.Key.class)
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ShipTypePaint {

  /** The ship type the paint fits. */
  @Id
  @Column(name = "ship_type_id", nullable = false)
  private UUID shipTypeId;

  /** The paint item. */
  @Id
  @Column(name = "game_item_id", nullable = false)
  private UUID gameItemId;

  /** The composite key of a link; both halves are {@code null} only while JPA fills a new key. */
  @Getter
  @Setter
  @EqualsAndHashCode
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** The ship type the paint fits. */
    @Nullable private UUID shipTypeId;

    /** The paint item. */
    @Nullable private UUID gameItemId;
  }
}
