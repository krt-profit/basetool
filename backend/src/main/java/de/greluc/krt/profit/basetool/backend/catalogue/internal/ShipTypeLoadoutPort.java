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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.Nullable;

/**
 * One port of a ship type's default loadout, the P4K's port tree (REQ-P4K-040): a fact of the game
 * data, written by the P4K import only, so it has no version column.
 */
@Entity
@Table(name = "ship_type_loadout_port")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ShipTypeLoadoutPort {

  /** The row id, assigned by the import so nested ports can reference their parent. */
  @Id private UUID id;

  /** The ship type whose default the tree describes. */
  @Column(name = "ship_type_id", nullable = false)
  private UUID shipTypeId;

  /** The port whose attachment carries this one, {@code null} for a top-level port. */
  @Nullable
  @Column(name = "parent_id")
  private UUID parentId;

  /** The place among its siblings, in catalogue order from 0. */
  @Column(nullable = false)
  private int position;

  /** The port name as the catalogue spells it. */
  @Column(nullable = false)
  private String name;

  /** The smallest item size the port takes, {@code null} when the game data gives none. */
  @Nullable
  @Column(name = "min_size")
  private Integer minSize;

  /** The largest item size the port takes, {@code null} when the game data gives none. */
  @Nullable
  @Column(name = "max_size")
  private Integer maxSize;

  /** The item types the port takes, each {@code Type} or {@code Type.SubType}, in stored order. */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private List<String> types;

  /** The port's flags without a leading {@code $}. */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  private List<String> flags;

  /** The tags an item must carry to fit the port, compared as stored. */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "required_tags", nullable = false, columnDefinition = "text[]")
  private List<String> requiredTags;

  /** The localisation key of the port's display name, kept as a key, or {@code null}. */
  @Nullable
  @Column(name = "display_name_key")
  private String displayNameKey;

  /** Where the attachment comes from; {@code null} for a value the import does not know. */
  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private ShipLoadoutPortSource source;

  /** The GUID of the entity the port holds by default, {@code null} for an empty port. */
  @Nullable
  @Column(name = "item_guid")
  private UUID itemGuid;

  /** The item row {@link #itemGuid} resolves to, {@code null} when there is none. */
  @Nullable
  @Column(name = "game_item_id")
  private UUID gameItemId;
}
