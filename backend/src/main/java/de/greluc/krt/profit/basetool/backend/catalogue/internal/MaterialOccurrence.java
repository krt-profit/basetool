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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One location where UEX says a material can be found (REQ-DATA-031).
 *
 * <p>Exactly one of the five location references is set; the {@code
 * ck_material_occurrence_one_location} constraint enforces it. Written only by {@link
 * UexCommodityOccurrenceSync}.
 */
@Entity
@Table(name = "material_occurrence")
@Getter
@Setter
@ToString(of = "id")
@NoArgsConstructor
public class MaterialOccurrence extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The material found at the location. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "material_id", nullable = false)
  private Material material;

  /** The star system, for a {@link MaterialOccurrenceKind#STAR_SYSTEM} occurrence. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "star_system_id")
  @Nullable
  private StarSystem starSystem;

  /** The planet, for a {@link MaterialOccurrenceKind#PLANET} occurrence. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "planet_id")
  @Nullable
  private Planet planet;

  /** The moon, for a {@link MaterialOccurrenceKind#MOON} occurrence. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "moon_id")
  @Nullable
  private Moon moon;

  /** The orbit, for a {@link MaterialOccurrenceKind#ORBIT} occurrence. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "orbit_id")
  @Nullable
  private Orbit orbit;

  /** The point of interest, for a {@link MaterialOccurrenceKind#POI} occurrence. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "poi_id")
  @Nullable
  private Poi poi;

  /**
   * Names the one location reference this row carries, without initialising the lazy reference.
   *
   * @return the kind and local id of the set reference
   * @throws IllegalStateException when no reference is set, which the schema constraint rules out
   *     for a stored row
   */
  @NotNull
  public MaterialOccurrenceTarget target() {
    if (starSystem != null) {
      return new MaterialOccurrenceTarget(MaterialOccurrenceKind.STAR_SYSTEM, starSystem.getId());
    }
    if (planet != null) {
      return new MaterialOccurrenceTarget(MaterialOccurrenceKind.PLANET, planet.getId());
    }
    if (moon != null) {
      return new MaterialOccurrenceTarget(MaterialOccurrenceKind.MOON, moon.getId());
    }
    if (orbit != null) {
      return new MaterialOccurrenceTarget(MaterialOccurrenceKind.ORBIT, orbit.getId());
    }
    if (poi != null) {
      return new MaterialOccurrenceTarget(MaterialOccurrenceKind.POI, poi.getId());
    }
    throw new IllegalStateException("Material occurrence " + id + " names no location");
  }
}
