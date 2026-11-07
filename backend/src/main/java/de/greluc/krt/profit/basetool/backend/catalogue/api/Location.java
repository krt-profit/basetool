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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/** Location JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Location extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Setter(onParam_ = @__(@Nullable))
  private UUID id;

  @Column(nullable = false, unique = true)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "city_id")
  @ToString.Exclude
  private City city;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "space_station_id")
  @ToString.Exclude
  private SpaceStation spaceStation;

  @Column(nullable = false)
  private Boolean hidden = false;

  /**
   * Admin-curated marker flagging this location as an in-game "home location" (a player residence /
   * primary spawn such as Lorville or Orison). Drives the hangar bulk "set home location" picker
   * ({@code GET /api/v1/locations/home-locations}). Maintained locally and never written by the UEX
   * universe sync, so it survives every sweep — the same guarantee {@link #hidden} relies on.
   */
  @Column(name = "is_home_location", nullable = false)
  private Boolean homeLocation = false;

  /**
   * Marks a place kept only so personal-inventory rows resolve (REQ-P4K-039): a city or space
   * station that is not live. No storage-place picker, list or exchange read offers such a row; the
   * UEX universe sync keeps its name and clears the marker once the place is live.
   */
  @Column(name = "reference_only", nullable = false)
  private Boolean referenceOnly = false;

  /**
   * The starmap object an admin made this storage place on (REQ-P4K-036), or {@code null} for a
   * place that hangs on a city or space station; set on create only.
   */
  @Column(name = "starmap_object_id", updatable = false)
  @Nullable
  private UUID starmapObjectId;
}
