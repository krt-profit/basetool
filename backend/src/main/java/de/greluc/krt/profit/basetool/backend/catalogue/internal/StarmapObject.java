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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.GameText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * A node of the game's location tree, imported from the P4K catalogue's {@code starmapObjects}
 * (REQ-P4K-034, ADR-0345): star systems, planets, moons, stations, jump points, Lagrange points and
 * POIs, P4K-only objects included. The P4K import writes every column but the admin's visibility
 * override (REQ-P4K-038); the UEX location tables link to it.
 */
@Entity
@Table(name = "starmap_object")
@Getter
@Setter
@NoArgsConstructor
public class StarmapObject extends AbstractEntity<UUID> implements P4kKeyedRow {

  /** Surrogate primary key, assigned by Hibernate before insert. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The P4K GUID, the object's identity and unique. */
  @Column(name = "p4k_uuid", nullable = false, unique = true)
  private UUID p4kUuid;

  /** The Record name without its type. */
  @Column(name = "class_name")
  @Nullable
  private String className;

  /** The record path without {@code .xml}. */
  @Column(name = "record_path", columnDefinition = "TEXT")
  @Nullable
  private String recordPath;

  /** The name of the object's {@code StarMapObjectType}, e.g. {@code Planet} or {@code Outpost}. */
  @Column(name = "type", length = 64)
  @Nullable
  private String type;

  /** The parent object, or {@code null} for a root or a parent the catalogue does not list. */
  @Column(name = "parent_id")
  @Nullable
  private UUID parentId;

  /** The folder of the star system that places the object, e.g. {@code stanton}. */
  @Column(name = "star_system", length = 64)
  @Nullable
  private String starSystem;

  /** The x coordinate in metres in the star system's frame, {@code null} without a position. */
  @Column(name = "position_x")
  @Nullable
  private Double positionX;

  /** The y coordinate in metres in the star system's frame, {@code null} without a position. */
  @Column(name = "position_y")
  @Nullable
  private Double positionY;

  /** The z coordinate in metres in the star system's frame, {@code null} without a position. */
  @Column(name = "position_z")
  @Nullable
  private Double positionZ;

  /** The Record's {@code hideInStarmap}, {@code null} when the Record states none. */
  @Column(name = "hide_in_starmap")
  @Nullable
  private Boolean hideInStarmap;

  /** The English name, a game text of the archive's localisation. */
  @Column(name = "name", columnDefinition = "TEXT")
  @Nullable
  @GameText
  private String name;

  /** The German name, a game text of the archive's localisation. */
  @Column(name = "name_de", columnDefinition = "TEXT")
  @Nullable
  @GameText
  private String nameDe;

  /** An admin's explicit visibility, winning over the rule (REQ-P4K-038), or {@code null}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "visibility_override", length = 8)
  @Nullable
  private StarmapVisibilityOverride visibilityOverride;

  /** The time of the last P4K match. */
  @Column(name = "p4k_synced_at")
  private Instant p4kSyncedAt;

  /** The P4K import run that last matched the object (REQ-P4K-005), or {@code null}. */
  @Column(name = "p4k_seen_run_id")
  @Nullable
  private UUID p4kSeenRunId;

  /**
   * When the sweep found the object gone from a complete catalogue (REQ-P4K-011), or {@code null}.
   */
  @Column(name = "p4k_deleted_at")
  @Nullable
  private Instant p4kDeletedAt;

  /** When an admin hid the object through a review case (REQ-P4K-016), or {@code null}. */
  @Column(name = "p4k_hidden_at")
  @Nullable
  private Instant p4kHiddenAt;

  /**
   * Whether the catalogue placed the object, so it has a position.
   *
   * @return {@code true} when all three coordinates are set
   */
  public boolean hasPosition() {
    return positionX != null && positionY != null && positionZ != null;
  }
}
